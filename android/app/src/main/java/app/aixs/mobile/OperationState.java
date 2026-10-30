package app.aixs.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

/** Persisted operation metadata; late output cannot overwrite a terminal result. */
final class OperationState {
  static JSONObject start(String id, String action, String agent, String origin, String title, long now) throws Exception {
    return new JSONObject().put("id", id).put("action", action).put("agent", agent == null ? "" : agent)
        .put("origin", origin).put("title", title).put("message", title)
        .put("status", "running").put("startedAt", now).put("updatedAt", now).put("steps", new JSONArray());
  }
  static JSONObject step(JSONObject previous, String message, long now) throws Exception {
    JSONObject next = new JSONObject(previous.toString());
    if (!next.optString("status").equals("running")) return next;
    JSONArray old = next.optJSONArray("steps"), steps = new JSONArray();
    if (old != null) for (int i = Math.max(0, old.length() - 7); i < old.length(); i++) steps.put(old.get(i));
    steps.put(new JSONObject().put("time", now).put("message", message));
    return next.put("message", message).put("steps", steps).put("updatedAt", now);
  }
  static JSONObject finish(JSONObject previous, String status, String message, long now) throws Exception {
    if (!status.equals("completed") && !status.equals("failed") && !status.equals("interrupted"))
      throw new IllegalArgumentException("无效操作结果");
    return new JSONObject(previous.toString()).put("status", status).put("message", message)
        .put("finishedAt", now).put("updatedAt", now);
  }
}
