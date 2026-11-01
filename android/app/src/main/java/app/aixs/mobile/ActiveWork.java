package app.aixs.mobile;

import java.util.HashMap;
import org.json.*;

/** Process-wide task lifecycle, independent of which screen is observing. */
final class ActiveWork {
  private final HashMap<String,Long> turns = new HashMap<>();
  private final HashMap<String,Long> attempts = new HashMap<>();
  private long revision;
  synchronized long checkpoint() { return revision; }
  synchronized void reconcile(JSONArray activeTurns, JSONArray activeAttempts, long checkpoint) {
    if(activeTurns!=null) turns.entrySet().removeIf(e -> e.getValue()<=checkpoint&&!contains(activeTurns,e.getKey()));
    if(activeAttempts!=null) attempts.entrySet().removeIf(e -> e.getValue()<=checkpoint&&!contains(activeAttempts,e.getKey()));
  }
  private boolean contains(JSONArray values,String id) {
    for(int i=0;i<values.length();i++)if(id.equals(values.optString(i)))return true;
    return false;
  }
  synchronized void clear() { turns.clear(); attempts.clear(); }
  synchronized boolean active() { return !turns.isEmpty() || !attempts.isEmpty(); }
  synchronized void observe(JSONObject event) {
    String method=event.optString("method"),type=event.optString("type");
    if(type.equals("native/stopped")){clear();return;}
    JSONObject p=event.optJSONObject("params");if(p==null)return;
    if(method.equals("turn/started")||method.equals("turn/completed")){
      JSONObject turn=p.optJSONObject("turn");if(turn==null)return;
      String id=p.optString("threadId")+":"+turn.optString("id");
      String attempt=event.optString("attemptId");
      if(attempt.isEmpty()) {
        if(method.equals("turn/started"))turns.put(id,++revision);else turns.remove(id);
      } else {
        if(method.equals("turn/started"))attempts.put(attempt,++revision);else attempts.remove(attempt);
      }
    }
  }
}
