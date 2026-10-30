package app.aixs.mobile;

import java.util.HashMap;
import java.util.LinkedHashMap;
import org.json.JSONObject;

/** Reuses the live transcript objects; keeps at most three recent views in RAM. */
final class AgentViewCache {
  private final LinkedHashMap<String, JSONObject> threads = new LinkedHashMap<>(4, .75f, true);
  private final HashMap<String, JSONObject> usage = new HashMap<>();
  private final HashMap<String, JSONObject> models = new HashMap<>();

  void remember(JSONObject thread, JSONObject tokens) {
    if (thread == null || thread.optString("id").isEmpty()) return;
    String id = thread.optString("id");
    threads.put(id, thread);
    if (tokens != null) usage.put(id, tokens);
    while (threads.size() > 3) {
      String oldest = threads.keySet().iterator().next();
      threads.remove(oldest);
      usage.remove(oldest);
    }
  }

  JSONObject thread(String id) { return threads.get(id); }
  JSONObject usage(String id) { return usage.get(id); }
  void forget(String id) { threads.remove(id); usage.remove(id); }
  void models(String engine, JSONObject value) { models.put(engine, value); }
  JSONObject models(String engine) { return models.get(engine); }
}
