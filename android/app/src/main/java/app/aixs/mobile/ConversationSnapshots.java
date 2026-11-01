package app.aixs.mobile;

import java.util.*;
import org.json.*;

/** Merge queue responses without discarding live turns or loaded history. */
final class ConversationSnapshots {
  /** Reloading an existing conversation must not replace live content with a cache miss. */
  static JSONObject forLoad(JSONObject current, JSONObject cached, String id) {
    if (current != null && id.equals(current.optString("id"))) return current;
    return cached != null && id.equals(cached.optString("id")) ? cached : null;
  }

  static void applyQueue(JSONObject current, JSONObject incoming) throws JSONException {
    if (current == null || incoming == null || !current.optString("id").equals(incoming.optString("id"))) return;
    incoming = CollaborationEvents.mergeSnapshot(current, incoming);
    for (String key : new String[]{"messageQueue", "executionControl"}) {
      if (incoming.has(key)) current.put(key, incoming.opt(key));
      else current.remove(key);
    }
    current.put("messageQueuePaused", incoming.optBoolean("messageQueuePaused"));
    JSONArray saved = current.optJSONArray("turns"), received = incoming.optJSONArray("turns");
    if (received == null) return;
    Map<String, JSONObject> next = new LinkedHashMap<>();
    for (int i = 0; i < received.length(); i++) {
      JSONObject turn = received.optJSONObject(i);
      if (turn != null) next.put(turn.optString("id"), turn);
    }
    JSONArray merged = new JSONArray();
    for (int i = 0; saved != null && i < saved.length(); i++) {
      JSONObject live = saved.optJSONObject(i);
      if (live == null) continue;
      JSONObject turn = next.remove(live.optString("id"));
      if (turn == null) turn = live; // A queue response cannot delete a turn.
      else if (live.optString("status").matches("completed|interrupted|failed|cancelled") && turn.optString("status").equals("inProgress")) turn = live;
      else preserveItems(live, turn);
      merged.put(turn);
    }
    for (JSONObject turn : next.values()) merged.put(turn);
    current.put("turns", merged);
  }

  private static void preserveItems(JSONObject live, JSONObject turn) throws JSONException {
    JSONArray old = live.optJSONArray("items"), items = turn.optJSONArray("items");
    if (old == null) return;
    if (items == null) { turn.put("items", old); return; }
    Map<String, JSONObject> received = new LinkedHashMap<>();
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      if (item != null) received.put(item.optString("id"), item);
    }
    JSONArray merged = new JSONArray();
    for (int i = 0; i < old.length(); i++) {
      JSONObject item = old.optJSONObject(i);
      if (item == null) continue;
      JSONObject replacement = received.remove(item.optString("id"));
      merged.put(replacement == null ? item : replacement);
    }
    for (JSONObject item : received.values()) merged.put(item);
    turn.put("items", merged);
  }
}
