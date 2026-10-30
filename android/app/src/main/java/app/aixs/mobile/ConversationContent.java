package app.aixs.mobile;

import org.json.*;

/** Read nullable Agent metadata without turning missing values into messages. */
final class ConversationContent {
  static long sumTurnStat(JSONObject thread, JSONObject saved, String key, String persistedKey) {
    long sum = 0;
    JSONArray turns = thread == null ? null : thread.optJSONArray("turns");
    for (int i = 0; turns != null && i < turns.length(); i++) {
      JSONObject turn = turns.optJSONObject(i);
      if (turn == null) continue;
      JSONObject entry = saved.optJSONObject(turn.optString("id"));
      long value = entry == null ? 0 : entry.optLong(key);
      sum += value > 0 ? value : turn.optLong(persistedKey, 0);
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
      if (value instanceof Number) return String.valueOf(((Number) value).longValue());
      if (value instanceof String && !((String) value).isEmpty()) return (String) value;
    }
    return "";
  }

  /** Epoch millis for sorting and day grouping; 0 when the value cannot be read. */
  static long stampMillis(String stamp) {
    if (stamp == null || stamp.isEmpty()) return 0;
    try {
      if (stamp.matches("\\d+")) {
        long value = Long.parseLong(stamp);
        return stamp.length() <= 10 ? value * 1000 : value;  // seconds vs millis
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
      for (String key : new String[] {"text", "message", "summary"})
        if (!object.isNull(key)) return text(object.opt(key));
      return object.toString();
    }
    return String.valueOf(value);
  }

  static String error(JSONObject turn) {
    return text(turn.opt("error")).trim();
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
    String command = string(item, "command", "");
    for (String key : new String[] {"aggregatedOutput", "progressMessage", "result", "arguments", "changes"}) {
      String output = text(item.opt(key));
      if (!output.isEmpty()) return command.isEmpty() ? output : command + "\n" + output;
    }
    return command;
  }
}
