package app.aixs.mobile;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RealtimeEventsTest {
  @Test public void nativeOutputsReviewHookAndGeneratedPathsAreNotBlank() throws Exception {
    String[] fixtures={"{\"type\":\"functionCallOutput\",\"name\":\"search\",\"output\":[{\"type\":\"input_text\",\"text\":\"found result\"}]}",
      "{\"type\":\"hookPrompt\",\"fragments\":[{\"text\":\"hook instruction\"}]}",
      "{\"type\":\"imageGeneration\",\"savedPath\":\"dist/image.png\",\"revisedPrompt\":\"a scene\"}",
      "{\"type\":\"exitedReviewMode\",\"review\":\"review findings\"}"};
    for(String value:fixtures){JSONObject item=new JSONObject(value);assertFalse(ConversationContent.item(item).isEmpty());assertFalse(RealtimeEvents.label(item,false).startsWith("工具 · "));}
  }
  @Test public void fileReportLabelCountsChangedFilesAndNamesTheEmptyCase() throws Exception {
    JSONObject report=new JSONObject("{\"type\":\"fileReport\",\"status\":\"completed\",\"counts\":{\"created\":1,\"modified\":2,\"deleted\":0}}");
    assertEquals("3 个文件有改动 · 已完成",RealtimeEvents.label(report,false));
    JSONObject empty=new JSONObject("{\"type\":\"fileReport\",\"status\":\"completed\",\"counts\":{\"created\":0,\"modified\":0,\"deleted\":0}}");
    assertEquals("本轮没有文件改动 · 已完成",RealtimeEvents.label(empty,false));
  }
  @Test public void childAndVerificationDetailsAreReadableAndFailedToolsStayFailed() throws Exception {
    JSONObject turn=new JSONObject();
    RealtimeEvents.apply(turn,"model/verification",new JSONObject("{\"verifications\":[{\"model\":\"worker\",\"status\":\"verified\"}]}"));
    assertTrue(ConversationContent.item(turn.getJSONArray("items").getJSONObject(0)).contains("worker"));
    RealtimeEvents.apply(turn,"item/agentMessage/delta",new JSONObject("{\"itemId\":\"child\",\"delta\":\"reply\",\"nativeChild\":true,\"sourceModel\":\"worker\"}"));
    assertEquals("subagentMessage",turn.getJSONArray("items").getJSONObject(1).getString("type"));
    RealtimeEvents.apply(turn,"item/completed",new JSONObject("{\"item\":{\"id\":\"tool\",\"type\":\"dynamicToolCall\",\"success\":false}}"));
    assertEquals("failed",turn.getJSONArray("items").getJSONObject(2).getString("status"));
    assertEquals("[图片结果]",ConversationContent.text(new JSONObject("{\"type\":\"image\",\"data\":\"private-base64\"}")));
  }
  @Test public void nullableNativeOutputDoesNotPrefixTheFirstDeltaWithNull() throws Exception {
    JSONObject turn=new JSONObject();
    RealtimeEvents.apply(turn,"item/started",new JSONObject("{\"item\":{\"id\":\"shell\",\"type\":\"commandExecution\",\"aggregatedOutput\":null}}"));
    RealtimeEvents.apply(turn,"item/commandExecution/outputDelta",new JSONObject("{\"itemId\":\"shell\",\"delta\":\"first\\n\"}"));
    assertEquals("first\n",turn.getJSONArray("items").getJSONObject(0).getString("aggregatedOutput"));
  }
  @Test public void toolDraftCannotLookLikeACompletedExecutionAfterRetry() throws Exception {
    JSONObject turn=new JSONObject();
    RealtimeEvents.apply(turn,"item/started",new JSONObject("{\"item\":{\"id\":\"draft\",\"type\":\"toolCallDraft\",\"tool\":\"bash\",\"status\":\"inProgress\"}}"));
    JSONObject draft=turn.getJSONArray("items").getJSONObject(0);
    assertTrue(RealtimeEvents.label(draft,true).startsWith("准备工具"));
    RealtimeEvents.apply(turn,"item/completed",new JSONObject("{\"item\":{\"id\":\"draft\",\"type\":\"runtimeStatus\",\"tool\":\"工具准备中断 · bash · 未执行\",\"status\":\"discarded\"}}"));
    assertEquals(1,turn.getJSONArray("items").length());
    assertEquals("discarded",draft.getString("status"));
    assertTrue(RealtimeEvents.label(draft,false).contains("未执行"));
    assertFalse(RealtimeEvents.label(draft,false).contains("已完成"));
  }
  @Test public void outputBeforeStartIsVisibleAndStartMergesWithoutErasingIt() throws Exception {
    JSONObject turn = new JSONObject();
    RealtimeEvents.apply(turn,"item/commandExecution/outputDelta",new JSONObject("{\"itemId\":\"c\",\"delta\":\"first\\n\"}"));
    RealtimeEvents.apply(turn,"item/started",new JSONObject("{\"item\":{\"id\":\"c\",\"type\":\"commandExecution\",\"command\":\"pwd\"}}"));
    RealtimeEvents.apply(turn,"item/commandExecution/outputDelta",new JSONObject("{\"itemId\":\"c\",\"delta\":\"second\"}"));
    JSONObject item=turn.getJSONArray("items").getJSONObject(0);
    assertEquals("first\nsecond",item.getString("aggregatedOutput"));
    assertEquals("second",RealtimeEvents.preview(item));
    assertTrue(RealtimeEvents.label(item,true).contains("进行中"));
    RealtimeEvents.apply(turn,"item/completed",new JSONObject("{\"item\":{\"id\":\"c\",\"type\":\"commandExecution\"}}"));
    assertEquals("completed",item.getString("status"));
  }
  @Test public void plansPatchesProgressAndHooksAppearBeforeCompletion() throws Exception {
    JSONObject turn = new JSONObject();
    RealtimeEvents.apply(turn,"item/plan/delta",new JSONObject("{\"itemId\":\"p\",\"delta\":\"Inspect\"}"));
    RealtimeEvents.apply(turn,"turn/plan/updated",new JSONObject("{\"plan\":[{\"step\":\"Read\",\"status\":\"inProgress\"}]}"));
    RealtimeEvents.apply(turn,"item/fileChange/patchUpdated",new JSONObject("{\"itemId\":\"f\",\"changes\":[{\"path\":\"a\",\"diff\":\"+new\"}]}"));
    RealtimeEvents.apply(turn,"item/mcpToolCall/progress",new JSONObject("{\"itemId\":\"m\",\"message\":\"Reading log\"}"));
    RealtimeEvents.apply(turn,"hook/started",new JSONObject("{\"run\":{\"id\":\"h\",\"eventName\":\"preToolUse\"}}"));
    assertEquals(5,turn.getJSONArray("items").length());
    assertTrue(turn.toString().contains("Reading log"));
    assertTrue(turn.toString().contains("+new"));
    RealtimeEvents.apply(turn,"item/commandExecution/terminalInteraction",new JSONObject("{\"itemId\":\"c\",\"stdin\":\"private-input\"}"));
    assertFalse(turn.toString().contains("private-input"));
  }
  @Test public void childItemsAreLabelledAsSubtasksWithoutDuplicatedPrefix() throws Exception {
    JSONObject reply=new JSONObject("{\"type\":\"subagentMessage\",\"parentToolUseId\":\"toolu\",\"sourceModel\":\"haiku\",\"status\":\"completed\"}");
    assertEquals("子任务回复 · 已完成",RealtimeEvents.label(reply,false));
    JSONObject progress=new JSONObject("{\"type\":\"taskProgress\",\"parentToolUseId\":\"toolu\",\"tool\":\"子任务\",\"status\":\"inProgress\"}");
    assertEquals("子任务进度 · 进行中",RealtimeEvents.label(progress,true));
    JSONObject activity=new JSONObject("{\"type\":\"subAgentActivity\",\"agentThreadId\":\"abc\",\"kind\":\"completed\"}");
    assertEquals("子代理 · 已完成",RealtimeEvents.label(activity,false));
    JSONObject spawn=new JSONObject("{\"type\":\"collabAgentToolCall\",\"tool\":\"spawnAgent\",\"model\":\"gpt-test\"}");
    assertEquals("启动子代理 · gpt-test",RealtimeEvents.label(spawn,false));
    assertFalse(RealtimeEvents.label(progress,true).contains("子任务 · 子任务"));
  }
  @Test public void subAgentActivityAndCollabStatesReadAsSentences() throws Exception {
    JSONObject activity=new JSONObject("{\"type\":\"subAgentActivity\",\"kind\":\"started\",\"agentPath\":\"/root/project/review\",\"agentThreadId\":\"1234567890abcdef\",\"model\":\"gpt-test\"}");
    String detail=ConversationContent.item(activity);
    assertTrue(detail.contains("/root/project/review"));
    assertTrue(detail.contains("线程 1234567890"));
    JSONObject spawn=new JSONObject("{\"type\":\"collabAgentToolCall\",\"prompt\":\"review auth\",\"receiverThreadIds\":[\"abcdef123456\"],\"agentsStates\":{\"abcdef123456\":{\"status\":\"inProgress\",\"message\":\"reading\"}}}");
    String states=ConversationContent.item(spawn);
    assertTrue(states.contains("review auth"));
    assertTrue(states.contains("进行中"));
    assertTrue(states.contains("reading"));
    assertFalse(states.contains("{\"status\""));
  }
  @Test public void childTurnSnapshotsMergeIntoTheParentWithoutWaitingForReload() throws Exception {
    JSONObject turn=new JSONObject();
    RealtimeEvents.apply(turn,"native/childTurn",new JSONObject("{\"threadId\":\"parent\",\"turnId\":\"turn-1\",\"sourceThreadId\":\"child-1\",\"sourceTurnId\":\"child-turn\",\"sourceEngine\":\"codex\",\"sourceModel\":\"gpt-test\",\"childTurn\":{\"id\":\"child-turn\",\"status\":\"completed\",\"items\":[{\"id\":\"c1\",\"type\":\"agentMessage\",\"text\":\"done\"}]},\"stats\":{\"toolCalls\":2,\"assistantMessages\":1}}"));
    JSONObject saved=turn.getJSONObject("nativeChildren").getJSONObject("child-1:child-turn");
    assertEquals("completed",saved.getJSONObject("turn").getString("status"));
    assertEquals(2,saved.getJSONObject("stats").getInt("toolCalls"));
    assertEquals("gpt-test",saved.getString("model"));
  }
  @Test public void reasoningPartsAndReadArgumentsHaveUsefulLiveContent() throws Exception {
    JSONObject turn=new JSONObject();
    RealtimeEvents.apply(turn,"item/reasoning/textDelta",new JSONObject("{\"itemId\":\"r\",\"delta\":\"Inspect \",\"contentIndex\":0}"));
    RealtimeEvents.apply(turn,"item/reasoning/textDelta",new JSONObject("{\"itemId\":\"r\",\"delta\":\"files\",\"contentIndex\":0}"));
    assertEquals("Inspect files",ConversationContent.item(turn.getJSONArray("items").getJSONObject(0)));
    JSONObject read=new JSONObject("{\"type\":\"dynamicToolCall\",\"tool\":\"Read\",\"status\":\"inProgress\",\"arguments\":{\"file_path\":\"/tmp/a\"},\"progressMessage\":\"执行中 · 2 秒\"}");
    assertEquals("/tmp/a · 执行中 · 2 秒",RealtimeEvents.preview(read));
    assertTrue(RealtimeEvents.label(read,true).contains("Read"));
  }
}
