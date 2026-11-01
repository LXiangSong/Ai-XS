package app.aixs.mobile;

import org.json.*;

/** Read nullable Agent metadata without turning missing values into messages. */
final class ConversationContent {
  /** Preserve input added while a queue edit RPC was in flight. */
  static JSONArray restoredInput(JSONArray queued, String draft, JSONArray attachments) throws JSONException {
    JSONArray result = queued == null ? new JSONArray() : new JSONArray(queued.toString());
    if (draft != null && !draft.isEmpty()) result.put(new JSONObject().put("type", "text").put("text", draft));
    for (int i = 0; attachments != null && i < attachments.length(); i++)
      result.put(new JSONObject(attachments.getJSONObject(i).toString()));
    return result;
  }
  static long durationMillis(JSONObject turn, long fallback) {
    if (turn == null) return Math.max(0, fallback);
    long duration = turn.optLong("durationMs", 0);
    if (duration > 0) return duration;
    long started = stampMillis(stamp(turn, "startedAt")), completed = stampMillis(stamp(turn, "completedAt"));
    return started > 0 && completed >= started ? completed - started : Math.max(0, fallback);
  }
  static long sumTurnStat(JSONObject thread, JSONObject saved, String key, String persistedKey) {
    long sum = 0;
    JSONArray turns = thread == null ? null : thread.optJSONArray("turns");
    for (int i = 0; turns != null && i < turns.length(); i++) {
      JSONObject turn = turns.optJSONObject(i);
      if (turn == null) continue;
      JSONObject entry = saved.optJSONObject(turn.optString("id"));
      long value = entry == null ? 0 : entry.optLong(key);
      sum += persistedKey.equals("durationMs") ? durationMillis(turn, value) : value > 0 ? value : turn.optLong(persistedKey, 0);
    }
    return sum;
  }
  static String editableText(JSONObject item) {
    StringBuilder text = new StringBuilder();
    JSONArray parts = item.optJSONArray("content");
    if (parts != null) for (int i = 0; i < parts.length(); i++) {
      JSONObject part = parts.optJSONObject(i);
      if (part == null || !"text".equals(part.optString("type"))) continue;
      if (text.length() > 0) text.append('\n');
      text.append(string(part, "text", ""));
    }
    return text.toString();
  }
  static String quote(String draft, String value) {
    String quoted = "> " + value.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\n> ");
    return (draft.isEmpty() ? "" : draft + (draft.endsWith("\n\n") ? "" : draft.endsWith("\n") ? "\n" : "\n\n")) + quoted + "\n\n";
  }
  static String string(JSONObject object, String key, String fallback) {
    if (object == null || object.isNull(key)) return fallback;
    Object value = object.opt(key);
    return value instanceof String ? (String) value : fallback;
  }

  /** Timestamps arrive as ISO strings (Codex/Claude) or epoch numbers (DeepSeek). */
  static String stamp(JSONObject object, String... keys) {
    if (object == null) return "";
    for (String key : keys) {
      if (object.isNull(key)) continue;
      Object value = object.opt(key);
      if (value instanceof Number) return value.toString();
      if (value instanceof String && !((String) value).isEmpty()) return (String) value;
    }
    return "";
  }

  /** Epoch millis for sorting and day grouping; 0 when the value cannot be read. */
  static long stampMillis(String stamp) {
    if (stamp == null || stamp.isEmpty()) return 0;
    try {
      if (stamp.matches("\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?")) {
        double value = Double.parseDouble(stamp);
        if (!Double.isFinite(value) || value <= 0) return 0;
        return Math.round(value < 100000000000D ? value * 1000 : value);
      }
      return java.time.Instant.parse(stamp).toEpochMilli();
    } catch (Exception ignored) {
      return 0;
    }
  }

  static String title(JSONObject thread) {
    String name = string(thread, "name", "").trim();
    if (!name.isEmpty()) return name;
    String preview = string(thread, "preview", "").trim();
    if (preview.isEmpty()) return "新对话";
    return preview.split("\\R", 2)[0];
  }

  static String text(Object value) {
    if (value == null || value == JSONObject.NULL) return "";
    if (value instanceof String) return (String) value;
    if (value instanceof JSONArray) {
      StringBuilder out = new StringBuilder();
      JSONArray parts = (JSONArray) value;
      for (int i = 0; i < parts.length(); i++) {
        String part = text(parts.opt(i));
        if (!part.isEmpty()) {
          if (out.length() > 0) out.append('\n');
          out.append(part);
        }
      }
      return out.toString();
    }
    if (value instanceof JSONObject) {
      JSONObject object = (JSONObject) value;
      String type=object.optString("type").toLowerCase(java.util.Locale.ROOT);
      if(type.contains("image"))return "[图片结果]";
      if(type.contains("audio"))return "[音频结果]";
      if(type.equals("content"))return text(object.opt("content"));
      for (String key : new String[] {"text", "message", "summary"})
        if (!object.isNull(key)) return text(object.opt(key));
      return object.toString();
    }
    return String.valueOf(value);
  }

  static String error(JSONObject turn) {
    return text(turn.opt("error")).trim();
  }

  static void appendReasoning(JSONObject item, JSONObject params, boolean summary) throws JSONException {
    String key = summary ? "summary" : "content";
    JSONArray parts = item.optJSONArray(key);
    if (parts == null) { parts = new JSONArray(); item.put(key, parts); }
    int index = Math.max(0, Math.min(100, params.optInt(summary ? "summaryIndex" : "contentIndex", 0)));
    while (parts.length() <= index) parts.put("");
    parts.put(index, text(parts.opt(index)) + params.optString("delta"));
  }

  /** A start RPC may return after its streaming events, even after completion. */
  static JSONObject startedTurn(JSONObject current, JSONObject snapshot) {
    if (current == null) return snapshot;
    JSONArray items = current.optJSONArray("items"), incoming = snapshot.optJSONArray("items");
    if (items == null) {
      items = new JSONArray();
      try { current.put("items", items); } catch (JSONException ignored) {}
    }
    for (int i = 0; i < items.length(); i++)
      if ("userMessage".equals(string(items.optJSONObject(i), "type", ""))) return current;
    JSONArray merged = new JSONArray();
    for (int i = 0; incoming != null && i < incoming.length(); i++) {
      JSONObject item = incoming.optJSONObject(i);
      if ("userMessage".equals(string(item, "type", ""))) merged.put(item);
    }
    if (merged.length() > 0) {
      for (int i = 0; i < items.length(); i++) merged.put(items.opt(i));
      try { current.put("items", merged); } catch (JSONException ignored) {}
    }
    return current;
  }

  static String item(JSONObject item) {
    String type = string(item, "type", "");
    if (type.equals("userMessage")) {
      JSONArray content = item.optJSONArray("content");
      StringBuilder out = new StringBuilder();
      if (content != null) for (int i = 0; i < content.length(); i++) {
        JSONObject part = content.optJSONObject(i);
        if (part == null) continue;
        String value = string(part, "text", "");
        if (value.isEmpty() && string(part, "type", "").toLowerCase(java.util.Locale.ROOT).contains("image")) value = "[图片]";
        if (!value.isEmpty()) {
          if (out.length() > 0) out.append('\n');
          out.append(value);
        }
      }
      return out.toString();
    }
    String direct = string(item, "text", "");
    if (!direct.isEmpty()) return direct;
    if (type.equals("reasoning")) {
      String summary = text(item.opt("summary"));
      String content = text(item.opt("content"));
      return summary.isEmpty() ? content : content.isEmpty() ? summary : summary + "\n\n" + content;
    }
    if (type.equals("agentMessage")) return text(item.opt("content"));
    if (type.equals("subAgentActivity")) {
      StringBuilder out = new StringBuilder();
      String path = string(item, "agentPath", "");
      if (!path.isEmpty()) out.append(path);
      String model = string(item, "model", "");
      String effort = string(item, "reasoningEffort", "");
      if (!model.isEmpty()) {
        if (out.length() > 0) out.append('\n');
        out.append(model).append(effort.isEmpty() ? "" : " · " + effort);
      }
      String thread = string(item, "agentThreadId", "");
      if (!thread.isEmpty()) {
        if (out.length() > 0) out.append('\n');
        out.append("线程 ").append(thread);
      }
      return out.toString();
    }
    if (type.equals("collabAgentToolCall")) {
      StringBuilder out = new StringBuilder();
      String prompt = string(item, "prompt", "");
      if (!prompt.isEmpty()) out.append(prompt.trim());
      JSONObject states = item.optJSONObject("agentsStates");
      for (java.util.Iterator<String> keys = states == null ? null : states.keys(); keys != null && keys.hasNext(); ) {
        String key = keys.next();
        JSONObject state = states.optJSONObject(key);
        if (out.length() > 0) out.append('\n');
        out.append(shortThread(key)).append(" · ").append(state == null ? "等待中" : statusText(string(state, "status", "")));
        String message = state == null ? "" : string(state, "message", "");
        if (!message.isEmpty()) out.append(" · ").append(message);
      }
      JSONArray receivers = item.optJSONArray("receiverThreadIds");
      if (out.length() == 0 && receivers != null) {
        for (int i = 0; i < receivers.length(); i++) {
          if (out.length() > 0) out.append('\n');
          out.append("子线程 ").append(shortThread(receivers.optString(i, "")));
        }
      }
      return out.toString();
    }
    String command = string(item, "command", "");
    StringBuilder detail = new StringBuilder(command);
    for (String key : new String[] {"query", "action", "path", "savedPath", "revisedPrompt", "review", "fragments", "prompt", "receiverThreadIds", "agentsStates", "agentThreadId", "agentPath", "kind", "partialInput", "arguments", "locations", "changes", "progressMessage", "aggregatedOutput", "contentItems", "output", "results", "result", "failure", "error"}) {
      if (key.equals("contentItems")) {
        JSONArray parts = item.optJSONArray(key);
        for (int i = 0; parts != null && i < parts.length(); i++) {
          JSONObject part = parts.optJSONObject(i);
          if (part == null) continue;
          String output = string(part, "text", "");
          String media = string(part, "type", "").toLowerCase(java.util.Locale.ROOT);
          if (output.isEmpty()) output = media.contains("image") ? "[图片结果]" : media.contains("audio") ? "[音频结果]" : "";
          if (!output.isEmpty()) { if (detail.length() > 0) detail.append('\n'); detail.append(output); }
        }
        continue;
      }
      String output = text(item.opt(key));
      if (output.isEmpty() || output.equals("{}") || output.equals("[]")) continue;
      // A result often repeats aggregatedOutput, but progress and input must stay visible.
      if (key.equals("result") && output.equals(text(item.opt("aggregatedOutput")))) continue;
      if (detail.length() > 0) detail.append('\n');
      detail.append(output);
    }
    return detail.toString();
  }

  private static String shortThread(String id) {
    if (id == null || id.isEmpty()) return "子线程";
    return id.length() > 10 ? id.substring(0, 8) + "…" : id;
  }

  private static String statusText(String status) {
    if ("completed".equals(status)) return "已完成";
    if ("failed".equals(status)) return "失败";
    if ("interrupted".equals(status)) return "已停止";
    if ("pending".equals(status) || status.isEmpty()) return "等待中";
    return "进行中";
  }
}
