package app.aixs.mobile;

import org.json.*;

/** The composer sends input, interrupts the active turn, or continues the same task. */
final class ExecutionButton {
  enum Action { SEND, STOP, RESUME, WAIT, IDLE }
  /** The backend dispatches only the first pending entry; failures block FIFO. */
  static boolean autoQueuePending(JSONObject thread) {
    if (thread == null || thread.optBoolean("messageQueuePaused")) return false;
    JSONObject control = thread.optJSONObject("executionControl");
    String state = control == null ? "" : control.optString("status");
    if (!state.equals("interrupted") && !state.equals("paused")) return false;
    JSONArray entries = thread.optJSONArray("messageQueue");
    for (int i = 0; entries != null && i < entries.length(); i++) {
      JSONObject entry = entries.optJSONObject(i);
      if (entry == null) continue;
      String status = entry.optString("status");
      if (status.matches("queued|dispatching|including|failed|needs_reconciliation"))
        return !entry.optBoolean("executionResume") && (status.equals("queued") || status.equals("dispatching"));
    }
    return false;
  }
  static Action action(boolean content, boolean active, String state) {
    if(content)return Action.SEND;
    if(state.equals("stopping")||state.equals("pausing")||state.equals("resuming"))return Action.WAIT;
    if(state.equals("interrupted")||state.equals("paused")||state.equals("failed")||state.equals("needs_reconciliation"))return Action.RESUME;
    return active?Action.STOP:Action.IDLE;
  }
}
