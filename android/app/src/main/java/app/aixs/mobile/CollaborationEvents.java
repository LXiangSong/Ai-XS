package app.aixs.mobile;

import java.util.*;
import org.json.*;

/** Apply worker events with the independent-mode reducer, isolated by execution attempt. */
final class CollaborationEvents {
  static JSONObject turn(JSONObject thread,String workflow) {
    JSONArray turns=thread==null?null:thread.optJSONArray("turns");
    for(int i=0;turns!=null&&i<turns.length();i++) {
      JSONObject value=turns.optJSONObject(i);
      if(value!=null&&workflow.equals(value.optString("workflowId")))return value;
    }
    return null;
  }
  private static JSONObject revisions(JSONObject turn)throws JSONException {
    JSONObject value=turn.optJSONObject("collaborationRevisions");
    if(value==null){value=new JSONObject();turn.put("collaborationRevisions",value);}return value;
  }
  private static boolean belongs(JSONObject item,String attempt) {
    return item!=null&&(attempt.equals(item.optString("attemptId"))||item.optString("id").startsWith(attempt+":"));
  }
  private static JSONObject safeItem(JSONObject raw)throws JSONException {
    JSONObject value=new JSONObject(raw.toString());value.remove("startedAt");return value;
  }
  private static void mergeTurn(JSONObject local,JSONObject raw,boolean completed)throws JSONException {
    JSONArray items=raw==null?null:raw.optJSONArray("items");
    for(int i=0;items!=null&&i<items.length();i++) {
      JSONObject item=items.optJSONObject(i);if(item==null||item.optString("type").equals("userMessage"))continue;
      RealtimeEvents.apply(local,completed?"item/completed":"item/started",new JSONObject().put("item",safeItem(item)));
    }
  }
  private static void replaceAttempt(JSONObject turn,String attempt,JSONArray replacement)throws JSONException {
    List<JSONObject> ordered=new ArrayList<>();JSONArray old=turn.optJSONArray("items");
    for(int i=0;old!=null&&i<old.length();i++) {
      JSONObject item=old.optJSONObject(i);if(item!=null&&!belongs(item,attempt))ordered.add(item);
    }
    for(int i=0;i<replacement.length();i++)ordered.add(replacement.getJSONObject(i));
    ordered.sort(Comparator.comparingLong((JSONObject i)->ConversationContent.stampMillis(ConversationContent.stamp(i,"startedAt")))
        .thenComparing(i->i.optString("id")));
    turn.put("items",new JSONArray(ordered));
  }
  static boolean apply(JSONObject thread,JSONObject event)throws JSONException {
    if(thread==null||!thread.optString("id").equals(event.optString("conversationId")))return false;
    JSONObject turn=turn(thread,event.optString("workflowId"));String attempt=event.optString("attemptId");
    long sequence=event.optLong("attemptSequence");
    if(turn==null||attempt.isEmpty()||sequence<=0)return false;
    JSONObject revision=revisions(turn);if(sequence<=revision.optLong(attempt))return false;
    JSONObject local=new JSONObject();JSONArray items=new JSONArray();local.put("items",items);
    JSONArray existing=turn.optJSONArray("items");
    for(int i=0;existing!=null&&i<existing.length();i++) {
      JSONObject item=existing.optJSONObject(i);if(!belongs(item,attempt))continue;
      JSONObject copy=new JSONObject(item.toString());String id=copy.optString("id");
      copy.put("id",id.startsWith(attempt+":")?id.substring(attempt.length()+1):id);items.put(copy);
    }
    String method=event.optString("method");JSONObject p=event.optJSONObject("params");
    if(p==null){revision.put(attempt,sequence);return false;}
    boolean changed=false;String attemptStatus="running";
    if(method.equals("turn/started")||method.equals("turn/completed")) {
      JSONObject raw=p.optJSONObject("turn");boolean completed=method.equals("turn/completed");mergeTurn(local,raw,completed);
      if(completed) {
        String status=raw==null?"failed":raw.optString("status","failed");
        attemptStatus=status.equals("completed")?"succeeded":status;
        for(int i=0;i<items.length();i++) {
          JSONObject item=items.getJSONObject(i);
          if(RealtimeEvents.active(item.optString("status")))item.put("status",status.equals("completed")?"completed":status);
        }
      }
      changed=true;
    } else if(method.equals("item/started")||method.equals("item/completed")) {
      JSONObject raw=p.optJSONObject("item");
      if(raw!=null&&!raw.optString("type").equals("userMessage")) {
        JSONObject params=new JSONObject(p.toString()).put("item",safeItem(raw));changed=RealtimeEvents.apply(local,method,params);
      }
    } else changed=RealtimeEvents.apply(local,method,p);
    revision.put(attempt,sequence);
    if(!changed)return false;
    double at=event.optDouble("receivedAt",System.currentTimeMillis()/1000.0);
    for(int i=0;i<items.length();i++) {
      JSONObject item=items.getJSONObject(i);item.put("id",attempt+":"+item.optString("id"));
      if(!item.has("startedAt"))item.put("startedAt",at);
      for(String key:new String[]{"attemptId","taskId","participantId","participant","engine","model","attemptNumber"})
        if(event.has(key))item.put(key,event.opt(key));
      item.put("attemptStatus",attemptStatus);
    }
    replaceAttempt(turn,attempt,items);return true;
  }

  /** A response started before a delta must not rewind or duplicate that member's output. */
  static JSONObject mergeSnapshot(JSONObject current,JSONObject incoming)throws JSONException {
    if(current==null||incoming==null||!current.optString("id").equals(incoming.optString("id")))return incoming;
    JSONArray turns=incoming.optJSONArray("turns");
    for(int i=0;turns!=null&&i<turns.length();i++) {
      JSONObject next=turns.optJSONObject(i);if(next==null||!next.has("workflowId"))continue;
      JSONObject live=turn(current,next.optString("workflowId"));if(live==null)continue;
      JSONObject saved=revisions(live),target=revisions(next);
      for(Iterator<String> keys=saved.keys();keys.hasNext();) {
        String attempt=keys.next();long sequence=saved.optLong(attempt);
        if(sequence<=target.optLong(attempt))continue;
        JSONArray replacement=new JSONArray(),items=live.optJSONArray("items");
        String terminal="";JSONArray received=next.optJSONArray("items");
        for(int j=0;received!=null&&j<received.length();j++) {
          JSONObject item=received.optJSONObject(j);if(!belongs(item,attempt))continue;
          String status=item.optString("attemptStatus");
          if(status.matches("succeeded|failed|interrupted|needs_reconciliation")){terminal=status;break;}
        }
        for(int j=0;items!=null&&j<items.length();j++)if(belongs(items.optJSONObject(j),attempt)) {
          JSONObject item=new JSONObject(items.getJSONObject(j).toString());
          if(!terminal.isEmpty()) {
            item.put("attemptStatus",terminal);
            if(RealtimeEvents.active(item.optString("status")))item.put("status",terminal.equals("succeeded")?"completed":terminal);
          }
          replacement.put(item);
        }
        replaceAttempt(next,attempt,replacement);target.put(attempt,sequence);
      }
    }
    return incoming;
  }
}
