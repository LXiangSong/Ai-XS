package app.aixs.mobile;

import java.util.*;
import org.json.*;

/** Preserve permission context independently of the adapter's tool naming. */
final class ApprovalDetails {
  static final class Section {
    final String title, text;
    final boolean code;
    Section(String title, String text, boolean code) {
      this.title = title; this.text = text; this.code = code;
    }
  }

  private static String format(Object value) {
    if (value == null || value == JSONObject.NULL) return "";
    try {
      if (value instanceof JSONObject) return ((JSONObject) value).toString(2);
      if (value instanceof JSONArray) return ((JSONArray) value).toString(2);
    } catch (JSONException ignored) {}
    return String.valueOf(value).trim();
  }

  private static void add(List<Section> out, String title, Object value, boolean code) {
    String text = format(value);
    if (!text.isEmpty() && !text.equals("{}") && !text.equals("[]")) out.add(new Section(title, text, code));
  }

  static List<Section> sections(JSONObject params) {
    ArrayList<Section> out = new ArrayList<>();
    String engine = params.optString("engine");
    String label = engine.equals("claude") ? "Claude Code" : engine.equals("deepseek") ? "DeepSeek"
        : engine.equals("codex") ? "Codex" : engine;
    add(out, "Agent", label, false);
    String tool = format(params.opt("tool"));
    if (tool.isEmpty()) tool = format(params.opt("toolName"));
    add(out, "操作", tool, false);
    Object raw = params.opt("arguments");
    if (raw == null || raw == JSONObject.NULL) raw = params.opt("rawInput");
    if (raw == null || raw == JSONObject.NULL) raw = params.opt("input");
    JSONObject args = raw instanceof JSONObject ? (JSONObject) raw : new JSONObject();
    Set<String> targets = new HashSet<>();
    for (JSONObject obj : new JSONObject[] {args, params})
      for (String key : new String[] {"file_path", "path", "target", "url"}) {
        String value = format(obj.opt(key));
        if (!value.isEmpty() && targets.add(value)) add(out, key.equals("url") ? "地址" : "目标路径", value, true);
      }
    Object command = args.opt("command");
    if (command == null || command == JSONObject.NULL || format(command).isEmpty()) command = params.opt("command");
    if (!format(command).equals(tool)) add(out, "命令 / 操作", command, true);
    add(out, "工作目录", params.opt("cwd"), true);
    Set<String> reasons = new HashSet<>();
    for (String key : new String[] {"reason", "description", "message", "decisionReason"}) {
      String value = format(params.opt(key));
      if (reasons.add(value)) add(out, "申请说明", value, false);
    }
    JSONObject rest = new JSONObject();
    Iterator<String> keys = args.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (!Arrays.asList("command", "file_path", "path", "target", "url").contains(key))
        try { rest.put(key, args.opt(key)); } catch (JSONException ignored) {}
    }
    add(out, "操作参数", raw instanceof JSONObject ? rest : raw, true);
    // Keep provider-specific permissions, diffs and unknown fields reviewable.
    JSONObject extra = new JSONObject();
    Set<String> known = new HashSet<>(Arrays.asList("engine", "tool", "toolName", "arguments", "rawInput", "input",
        "command", "cwd", "file_path", "path", "target", "url", "reason", "description", "message", "decisionReason",
        "threadId", "turnId", "itemId", "callId", "approvalId", "requestedSchema", "schema"));
    keys = params.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      if (!known.contains(key)) try { extra.put(key, params.opt(key)); } catch (JSONException ignored) {}
    }
    add(out, "其他详情", extra, true);
    if (out.isEmpty()) add(out, "操作详情", "Agent 未提供操作参数，请拒绝后让 Agent 说明具体操作。", false);
    return out;
  }
}
