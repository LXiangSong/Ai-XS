package app.aixs.mobile;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CollaborationEventsTest {
  @Test public void budgetClockAndUnchangedTransportEventsDoNotRebuildMarkdownViews() throws Exception {
    JSONObject thread = thread(), turn = turn(thread);
    turn.put("budgetUsage", new JSONObject().put("elapsedSeconds", 1));
    String before = RealtimeEvents.renderSignature(turn, false, true, null);
    turn.getJSONObject("budgetUsage").put("elapsedSeconds", 2);
    turn.put("collaborationRevisions", new JSONObject().put("a", 5));
    assertEquals(before, RealtimeEvents.renderSignature(turn, false, true, before));
    turn.put("collaborationStatus", "paused");
    assertNotEquals(before, RealtimeEvents.renderSignature(turn, false, true, before));
  }
  private JSONObject thread()throws Exception {
    return new JSONObject("{\"id\":\"chat\",\"turns\":[{\"id\":\"round\",\"workflowId\":\"workflow\",\"status\":\"completed\",\"collaborationStatus\":\"running\",\"items\":[{\"id\":\"user\",\"type\":\"userMessage\",\"content\":[{\"type\":\"text\",\"text\":\"user goal\"}]}]}]}");
  }
  private JSONObject event(String attempt,long seq,String method,String params)throws Exception {
    return new JSONObject().put("conversationId","chat").put("workflowId","workflow").put("attemptId",attempt)
        .put("attemptSequence",seq).put("participant",attempt).put("participantId",attempt).put("engine","codex")
        .put("model","model-"+attempt).put("taskId","task-"+attempt).put("attemptNumber",1).put("receivedAt",seq+10)
        .put("method",method).put("params",new JSONObject(params));
  }
  private JSONObject turn(JSONObject thread)throws Exception{return thread.getJSONArray("turns").getJSONObject(0);}
  private JSONObject item(JSONObject thread,String id)throws Exception {
    JSONArray items=turn(thread).getJSONArray("items");
    for(int i=0;i<items.length();i++)if(items.getJSONObject(i).optString("id").equals(id))return items.getJSONObject(i);
    throw new AssertionError("Missing item "+id);
  }
  @Test public void completedInlineTurnStillRepaintsEveryNewOrEqualLengthUpdate()throws Exception {
    JSONObject thread=thread(),turn=turn(thread);String first=RealtimeEvents.renderSignature(turn,false,true,"cached");
    CollaborationEvents.apply(thread,event("a",1,"item/reasoning/textDelta","{\"itemId\":\"think\",\"delta\":\"read\"}"));
    String next=RealtimeEvents.renderSignature(turn,false,true,first);assertNotEquals(first,next);
    CollaborationEvents.apply(thread,event("a",2,"item/completed","{\"item\":{\"id\":\"think\",\"type\":\"reasoning\",\"content\":[\"done\"]}}"));
    assertNotEquals(next,RealtimeEvents.renderSignature(turn,false,true,next));
    assertEquals("completed",turn.getString("status"));
    assertEquals("cached",RealtimeEvents.renderSignature(new JSONObject("{\"status\":\"completed\"}"),false,true,"cached"));
  }
  @Test public void simultaneousMembersKeepReasoningCommandsAndToolsSeparate()throws Exception {
    JSONObject thread=thread();
    for(String a:new String[]{"one","two"}) {
      CollaborationEvents.apply(thread,event(a,1,"item/reasoning/textDelta","{\"itemId\":\"think\",\"delta\":\"inspect \"}"));
      CollaborationEvents.apply(thread,event(a,2,"item/started","{\"item\":{\"id\":\"command\",\"type\":\"commandExecution\",\"command\":\"pwd\",\"aggregatedOutput\":null}}"));
      assertEquals("pwd",item(thread,a+":command").getString("command"));
      CollaborationEvents.apply(thread,event(a,3,"item/commandExecution/outputDelta","{\"itemId\":\"command\",\"delta\":\"/project\\n\"}"));
      CollaborationEvents.apply(thread,event(a,4,"item/mcpToolCall/progress","{\"itemId\":\"tool\",\"message\":\"reading files\"}"));
      assertEquals("inspect ",ConversationContent.item(item(thread,a+":think")));
      assertEquals("/project\n",item(thread,a+":command").getString("aggregatedOutput"));
      assertEquals("reading files",item(thread,a+":tool").getString("progressMessage"));
      assertEquals("model-"+a,item(thread,a+":command").getString("model"));
    }
    assertEquals(7,turn(thread).getJSONArray("items").length());
  }
  @Test public void staleReloadAndDuplicateEventsCannotRewindOrRepeatDeltas()throws Exception {
    JSONObject live=thread(),old=thread();
    JSONObject first=event("a",1,"item/agentMessage/delta","{\"itemId\":\"reply\",\"delta\":\"hel\"}");
    CollaborationEvents.apply(live,first);
    old=new JSONObject(live.toString());
    JSONObject next=event("a",2,"item/agentMessage/delta","{\"itemId\":\"reply\",\"delta\":\"lo\"}");
    CollaborationEvents.apply(live,next);
    JSONObject merged=CollaborationEvents.mergeSnapshot(live,old);
    assertEquals("hello",item(merged,"a:reply").getString("text"));
    assertFalse(CollaborationEvents.apply(merged,next));assertFalse(CollaborationEvents.apply(merged,first));
    assertEquals("hello",item(merged,"a:reply").getString("text"));
    JSONObject finalSnapshot=new JSONObject(merged.toString());
    turn(finalSnapshot).getJSONObject("collaborationRevisions").put("a",3);
    item(finalSnapshot,"a:reply").put("text","final").put("status","completed");
    assertEquals("final",item(CollaborationEvents.mergeSnapshot(merged,finalSnapshot),"a:reply").getString("text"));
  }
  @Test public void completionSettlesOnlyThatAttemptAndWorkerPromptNeverAppears()throws Exception {
    JSONObject thread=thread();
    for(String a:new String[]{"a","b"})CollaborationEvents.apply(thread,event(a,1,"item/started","{\"item\":{\"id\":\"cmd\",\"type\":\"commandExecution\"}}"));
    CollaborationEvents.apply(thread,event("a",2,"item/started","{\"item\":{\"id\":\"private\",\"type\":\"userMessage\",\"text\":\"internal prompt\"}}"));
    CollaborationEvents.apply(thread,event("a",3,"turn/completed","{\"turn\":{\"status\":\"interrupted\",\"items\":[]}}"));
    assertFalse(thread.toString().contains("internal prompt"));
    assertEquals("interrupted",item(thread,"a:cmd").getString("status"));
    assertEquals("inProgress",item(thread,"b:cmd").getString("status"));
    JSONObject foreign=event("a",4,"item/agentMessage/delta","{\"itemId\":\"reply\",\"delta\":\"wrong chat\"}").put("conversationId","other");
    assertFalse(CollaborationEvents.apply(thread,foreign));assertFalse(thread.toString().contains("wrong chat"));
  }
  @Test public void recoveredTerminalStatusWinsEvenWhenItsDiskSnapshotHasOlderText()throws Exception {
    JSONObject live=thread();CollaborationEvents.apply(live,event("a",1,"item/started","{\"item\":{\"id\":\"cmd\",\"type\":\"commandExecution\"}}"));
    JSONObject disk=new JSONObject(live.toString());
    CollaborationEvents.apply(live,event("a",2,"item/commandExecution/outputDelta","{\"itemId\":\"cmd\",\"delta\":\"latest output\"}"));
    item(disk,"a:cmd").put("attemptStatus","interrupted");
    JSONObject recovered=CollaborationEvents.mergeSnapshot(live,disk);
    assertEquals("latest output",item(recovered,"a:cmd").getString("aggregatedOutput"));
    assertEquals("interrupted",item(recovered,"a:cmd").getString("attemptStatus"));
    assertEquals("interrupted",item(recovered,"a:cmd").getString("status"));
  }
}
