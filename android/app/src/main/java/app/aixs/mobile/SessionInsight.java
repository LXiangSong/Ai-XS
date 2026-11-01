package app.aixs.mobile;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Aggregates one conversation for the 会话详情 panel: files the turn inventory
 * reported, native sub-agents (Codex child threads, Claude tasks, DeepSeek child
 * sessions) and the sources their tools actually named. It only reads records
 * the agents emitted; missing data stays missing instead of being guessed.
 */
final class SessionInsight {
  static final class File {
    String path = "";
    String kind = "modified";
    String verification = "";
    String root = "";
    String turnId = "";
    int turn;
    int occurrences = 1;
    long at;
    final LinkedHashSet<String> members = new LinkedHashSet<>();
  }

  static final class ChildActivity {
    String label = "";
    String detail = "";
    String status = "completed";
    long at;
  }

  static final class Subagent {
    String key = "";
    String engine = "";
    String model = "";
    String title = "";
    String preview = "";
    String threadId = "";
    String status = "pending";
    String turnId = "";
    int turn;
    int tools;
    int messages;
    int reasoning;
    int turnCount;
    long durationMs;
    long startedAtMs;
    long finishedAtMs;
    boolean summaryOnly;
    final ArrayList<ChildActivity> log = new ArrayList<>();

    boolean active() { return SessionInsight.active(status); }
  }

  static final class Source {
    String label = "";
    String detail = "";
    String kind = "file";
    String turnId = "";
    int turn;
    int occurrences = 1;
  }

  final ArrayList<File> files = new ArrayList<>();
  final ArrayList<Subagent> subagents = new ArrayList<>();
  final ArrayList<Source> sources = new ArrayList<>();
  final ArrayList<String> ignored = new ArrayList<>();
  String title = "";
  String cwd = "";
  String engine = "";
  String model = "";
  int turns;
  int created;
  int modified;
  int deleted;
  int activeSubagents;
  long durationMs;
  long tokens;
  long updatedAtMs;

  private final LinkedHashMap<String, File> fileIndex = new LinkedHashMap<>();
  private final LinkedHashMap<String, Subagent> childIndex = new LinkedHashMap<>();
  private final LinkedHashMap<String, Source> sourceIndex = new LinkedHashMap<>();

  static SessionInsight of(JSONObject thread) {
    SessionInsight insight = new SessionInsight();
    if (thread == null) return insight;
    insight.title = ConversationContent.title(thread);
    insight.cwd = ConversationContent.string(thread, "cwd", "");
    insight.engine = ConversationContent.string(thread, "engine", "");
    insight.model = ConversationContent.string(thread, "model", "");
    insight.updatedAtMs = ConversationContent.stampMillis(ConversationContent.stamp(thread, "updatedAt", "createdAt"));
    JSONObject usage = thread.optJSONObject("usage");
    JSONObject total = usage == null ? null : usage.optJSONObject("total");
    if (total == null && usage != null) total = usage;
    insight.tokens = total == null ? 0 : Math.max(0, Math.round(total.optDouble("totalTokens",
        total.optDouble("inputTokens", 0) + total.optDouble("outputTokens", 0))));
    JSONArray turns = thread.optJSONArray("turns");
    int count = turns == null ? 0 : turns.length();
    insight.turns = count;
    for (int i = 0; i < count; i++) {
      JSONObject turn = turns.optJSONObject(i);
      if (turn == null) continue;
      int number = i + 1;
      String turnEngine = ConversationContent.string(turn, "engine", insight.engine);
      String turnModel = ConversationContent.string(turn, "model", insight.model);
      if (insight.engine.isEmpty()) insight.engine = turnEngine;
      if (insight.model.isEmpty()) insight.model = turnModel;
      String turnId = ConversationContent.string(turn, "id", String.valueOf(number));
      insight.durationMs += ConversationContent.durationMillis(turn, 0);
      JSONArray items = turn.optJSONArray("items");
      // Collaboration runs against one shared project. Do not expose a member's
      // partial inventory in 会话详情 until the workflow has stopped.
      boolean deferFiles = turn.has("workflowId") && !collaborationFilesReady(turn.optString("collaborationStatus"));
      boolean reported = false;
      for (int j = 0; !deferFiles && items != null && j < items.length(); j++) {
        JSONObject item = items.optJSONObject(j);
        if (item == null) continue;
        String type = ConversationContent.string(item, "type", "");
        if (type.equals("fileReport")) {
          reported = true;
          insight.report(item, number, turnId);
        }
      }
      for (int j = 0; items != null && j < items.length(); j++) {
        JSONObject item = items.optJSONObject(j);
        if (item == null) continue;
        String type = ConversationContent.string(item, "type", "");
        if (!deferFiles && !reported && (type.equals("fileChange") || type.equals("turnDiff"))) insight.patch(item, number, turnId);
        insight.child(item, number, turnId, turnEngine, turnModel);
        insight.source(item, number, turnId);
      }
      insight.children(turn, number, turnId, turnEngine, turnModel);
    }
    insight.finish();
    return insight;
  }

  private static boolean collaborationFilesReady(String status) {
    return status.equals("completed") || status.equals("cancelled") || status.equals("failed")
        || status.equals("interrupted") || status.equals("awaiting_acceptance")
        || status.equals("partial_failure") || status.equals("needs_reconciliation")
        || status.equals("succeeded");
  }

  private void report(JSONObject item, int turn, String turnId) {
    JSONArray changes = item.optJSONArray("changes");
    for (int i = 0; changes != null && i < changes.length(); i++) {
      JSONObject change = changes.optJSONObject(i);
      if (change == null) continue;
      String path = ConversationContent.string(change, "path", "");
      if (path.isEmpty()) continue;
      String root = ConversationContent.string(item, "root", "");
      String fileKey = root.isEmpty() ? path : root + "\u0000" + path;
      File file = fileIndex.get(fileKey);
      if (file == null) {
        file = new File();
        file.path = path;
        file.root = root;
        file.turn = turn;
        file.turnId = turnId;
        fileIndex.put(fileKey, file);
      } else {
        ++file.occurrences;
      }
      file.kind = normalizeKind(ConversationContent.string(change, "kind", "modified"));
      String participant = ConversationContent.string(item, "participant", "");
      if (participant.isEmpty()) participant = ConversationContent.string(item, "participantId", "");
      if (!participant.isEmpty()) file.members.add(participant);
      file.verification = ConversationContent.string(change, "verification", "");
      file.turn = turn;
      file.turnId = turnId;
      file.at = ConversationContent.stampMillis(ConversationContent.stamp(item, "completedAt", "startedAt"));
    }
    JSONArray excluded = item.optJSONArray("excluded");
    for (int i = 0; excluded != null && i < excluded.length() && ignored.size() < 12; i++) {
      String value = excluded.optString(i, "");
      if (!value.isEmpty() && !ignored.contains(value)) ignored.add(value);
    }
  }

  /** Fallback for older transcripts without a turn file inventory. */
  private void patch(JSONObject item, int turn, String turnId) {
    JSONArray changes = item.optJSONArray("changes");
    for (int i = 0; changes != null && i < changes.length(); i++) {
      JSONObject change = changes.optJSONObject(i);
      if (change == null) continue;
      String path = ConversationContent.string(change, "path", "");
      if (path.isEmpty()) continue;
      String root = ConversationContent.string(item, "root", "");
      String fileKey = root.isEmpty() ? path : root + "\u0000" + path;
      File file = fileIndex.get(fileKey);
      if (file == null) {
        file = new File();
        file.path = path;
        file.root = root;
        file.turn = turn;
        file.turnId = turnId;
        fileIndex.put(fileKey, file);
      } else {
        ++file.occurrences;
      }
      file.kind = normalizeKind(change.opt("kind"));
      file.verification = "agent";
      file.turn = turn;
      file.turnId = turnId;
    }
  }

  private static String normalizeKind(Object value) {
    String kind = "";
    if (value instanceof JSONObject) kind = ConversationContent.string((JSONObject) value, "type", "");
    else if (value instanceof String) kind = (String) value;
    kind = kind.toLowerCase(Locale.ROOT);
    if (kind.matches("add|added|create|created|new")) return "created";
    if (kind.matches("delete|deleted|remove|removed")) return "deleted";
    return "modified";
  }

  private Subagent child(String key, int turn, String turnId, String engine, String model) {
    Subagent child = childIndex.get(key);
    if (child == null) {
      child = new Subagent();
      child.key = key;
      child.engine = engine == null ? "" : engine;
      child.model = model == null ? "" : model;
      child.turn = turn;
      child.turnId = turnId;
      childIndex.put(key, child);
      subagents.add(child);
    }
    if (turn > child.turn) {
      child.turn = turn;
      child.turnId = turnId;
    }
    return child;
  }

  private void child(JSONObject item, int turn, String turnId, String turnEngine, String turnModel) {
    String type = ConversationContent.string(item, "type", "");
    String sourceThread = ConversationContent.string(item, "sourceThreadId", "");
    String parentTool = ConversationContent.string(item, "parentToolUseId", "");
    String engine = ConversationContent.string(item, "sourceEngine", turnEngine);
    String model = ConversationContent.string(item, "sourceModel", turnModel);

    if (type.equals("collabAgentToolCall")) {
      JSONArray receivers = item.optJSONArray("receiverThreadIds");
      String prompt = ConversationContent.string(item, "prompt", "");
      String requested = ConversationContent.string(item, "model", "");
      JSONObject states = item.optJSONObject("agentsStates");
      for (int i = 0; receivers != null && i < receivers.length(); i++) {
        String id = receivers.optString(i, "");
        if (id.isEmpty()) continue;
        Subagent child = child("codex:" + id, turn, turnId, "codex", requested.isEmpty() ? model : requested);
        child.threadId = id;
        child.model = requested.isEmpty() ? child.model : requested;
        if (child.title.isEmpty()) child.title = titleFromPrompt(prompt);
        JSONObject state = states == null ? null : states.optJSONObject(id);
        if (state != null) {
          child.status = normalizeStatus(ConversationContent.string(state, "status", ""));
          if (child.preview.isEmpty()) child.preview = ConversationContent.string(state, "message", "");
        } else child.status = normalizeStatus(ConversationContent.string(item, "status", ""));
      }
      return;
    }
    if (type.equals("subAgentActivity")) {
      String id = ConversationContent.string(item, "agentThreadId", "");
      if (id.isEmpty()) return;
      Subagent child = child("codex:" + id, turn, turnId, "codex", model);
      child.threadId = id;
      String path = ConversationContent.string(item, "agentPath", "");
      if (!path.isEmpty() && child.title.isEmpty()) child.title = lastSegment(path);
      String kind = ConversationContent.string(item, "kind", "");
      if (kind.equals("started") || kind.equals("interacted")) child.status = "inProgress";
      else if (kind.equals("interrupted")) child.status = "interrupted";
      else if (kind.equals("completed")) child.status = "completed";
      ChildActivity log = activity(child, "子代理" + activityName(kind), "", turn);
      log.status = child.status;
      return;
    }
    String key = !sourceThread.isEmpty() ? engine + ":" + sourceThread
        : !parentTool.isEmpty() ? "tool:" + engine + ":" + parentTool : "";
    if (key.isEmpty()) return;
    Subagent child = child(key, turn, turnId, engine, model);
    if (!sourceThread.isEmpty()) child.threadId = sourceThread;
    if (!ConversationContent.string(item, "sourceModel", "").isEmpty()) child.model = model;
    String status = item.optString("status", "");
    if (!status.isEmpty() && !type.equals("reasoning") && !type.equals("agentMessage") && !type.equals("subagentMessage")) {
      child.status = normalizeStatus(status);
    }
    if (type.equals("taskProgress")) {
      child.status = normalizeStatus(status);
      if (child.title.isEmpty()) child.title = ConversationContent.string(item, "tool", "");
    }
    if (type.equals("subagentMessage")) {
      ++child.messages;
      child.preview = oneLine(ConversationContent.item(item), 160);
      if (item.optBoolean("summaryOnly")) child.summaryOnly = true;
      if (child.title.isEmpty()) child.title = item.optBoolean("summaryOnly") ? "子任务摘要" : "子任务回复";
    } else if (type.equals("reasoning")) {
      ++child.reasoning;
    } else if (type.equals("commandExecution") || type.equals("dynamicToolCall") || type.equals("mcpToolCall")
        || type.equals("functionCall") || type.equals("webSearch") || type.equals("imageView")
        || type.equals("fileChange") || type.equals("toolCallDraft")) {
      ++child.tools;
      String preview = RealtimeEvents.preview(item);
      if (!preview.isEmpty()) child.preview = oneLine(preview, 160);
    }
    if (!type.equals("taskProgress") && !type.equals("runtimeStatus") && !type.equals("subAgentActivity")) {
      String detail = ConversationContent.item(item);
      String label = RealtimeEvents.label(item, false);
      if (!detail.trim().isEmpty() || active(status)) activity(child, label, detail, turn);
    }
    long at = ConversationContent.stampMillis(ConversationContent.stamp(item, "startedAtMs", "startedAt"));
    if (at <= 0) at = ConversationContent.stampMillis(ConversationContent.stamp(item, "completedAtMs", "completedAt"));
    if (at > 0) {
      if (child.startedAtMs == 0 || at < child.startedAtMs) child.startedAtMs = at;
      if (active(status)) child.finishedAtMs = 0;
      else if (at > child.finishedAtMs) child.finishedAtMs = at;
    }
  }

  private static ChildActivity activity(Subagent child, String label, String detail, int turn) {
    ChildActivity log = new ChildActivity();
    log.label = label;
    log.detail = detail == null ? "" : detail;
    child.log.add(log);
    if (child.log.size() > 60) child.log.remove(0);
    return log;
  }

  /** Completed child snapshots survive even when their live items were trimmed. */
  private void children(JSONObject turn, int number, String turnId, String turnEngine, String turnModel) {
    JSONObject saved = turn.optJSONObject("nativeChildren");
    for (Iterator<String> keys = saved == null ? null : saved.keys(); keys != null && keys.hasNext();) {
      String key = keys.next();
      JSONObject entry = saved.optJSONObject(key);
      if (entry == null) continue;
      String engine = ConversationContent.string(entry, "engine", turnEngine);
      String thread = ConversationContent.string(entry, "sourceThreadId", "");
      String model = ConversationContent.string(entry, "model", turnModel);
      String childKey = !thread.isEmpty() ? engine + ":" + thread : "tool:" + engine + ":" + key;
      Subagent child = child(childKey, number, turnId, engine, model);
      if (!thread.isEmpty()) child.threadId = thread;
      if (!model.isEmpty()) child.model = model;
      JSONObject stats = entry.optJSONObject("stats");
      JSONObject childTurn = entry.optJSONObject("turn");
      if (stats != null) {
        child.tools = Math.max(child.tools, stats.optInt("toolCalls", 0));
        child.messages = Math.max(child.messages, stats.optInt("assistantMessages", 0));
        child.reasoning = Math.max(child.reasoning, stats.optInt("reasoningItems", 0));
        child.turnCount = Math.max(child.turnCount, stats.optInt("turns", 0));
        long duration = stats.optLong("durationMs", 0);
        if (duration > 0) child.durationMs = duration;
        child.summaryOnly |= stats.optBoolean("partial", false);
        JSONObject statuses = stats.optJSONObject("statuses");
        String derived = statusFrom(statuses);
        if (!derived.isEmpty()) child.status = derived;
        if (stats.optInt("active", 0) > 0) child.status = "inProgress";
      }
      if (childTurn != null) {
        String status = ConversationContent.string(childTurn, "status", "");
        if (!status.isEmpty()) child.status = normalizeStatus(status);
        long started = ConversationContent.stampMillis(ConversationContent.stamp(childTurn, "startedAt"));
        long finished = ConversationContent.stampMillis(ConversationContent.stamp(childTurn, "completedAt"));
        if (started > 0) child.startedAtMs = started;
        if (finished > 0) child.finishedAtMs = finished;
        if (child.durationMs == 0) child.durationMs = ConversationContent.durationMillis(childTurn, 0);
        JSONArray items = childTurn.optJSONArray("items");
        for (int i = 0; items != null && i < items.length() && child.log.size() < 20; i++) {
          JSONObject item = items.optJSONObject(i);
          if (item == null) continue;
          String detail = ConversationContent.item(item);
          if (detail.trim().isEmpty()) continue;
          activity(child, RealtimeEvents.label(item, child.active()), detail, number);
        }
      }
    }
  }

  private static String statusFrom(JSONObject statuses) {
    if (statuses == null) return "";
    if (statuses.optInt("failed", 0) > 0) return "failed";
    if (statuses.optInt("interrupted", 0) > 0) return "interrupted";
    if (statuses.optInt("inProgress", 0) > 0 || statuses.optInt("pending", 0) > 0) return "inProgress";
    if (statuses.optInt("completed", 0) > 0) return "completed";
    return "";
  }

  private void source(JSONObject item, int turn, String turnId) {
    String type = ConversationContent.string(item, "type", "");
    if (type.equals("imageView")) {
      addSource("读取图片", ConversationContent.string(item, "path", ""), "file", "", turn, turnId);
      return;
    }
    if (type.equals("agentMessage")) {
      JSONObject citation = item.optJSONObject("memoryCitation");
      JSONArray entries = citation == null ? null : citation.optJSONArray("entries");
      for (int i = 0; entries != null && i < entries.length(); i++) {
        JSONObject entry = entries.optJSONObject(i);
        if (entry == null) continue;
        String path = ConversationContent.string(entry, "path", "");
        String note = ConversationContent.string(entry, "note", "");
        int start = entry.optInt("lineStart", 0);
        int end = entry.optInt("lineEnd", 0);
        String detail = (start > 0 ? "第 " + start + (end > start ? "-" + end : "") + " 行" : "") + (note.isEmpty() ? "" : (start > 0 ? " · " : "") + note);
        addSource(path, detail, "citation", "", turn, turnId);
      }
      return;
    }
    if (type.equals("webSearch")) {
      web(item, turn, turnId);
      return;
    }
    if (type.equals("commandExecution")) {
      JSONArray actions = item.optJSONArray("commandActions");
      boolean described = false;
      for (int i = 0; actions != null && i < actions.length(); i++) {
        JSONObject action = actions.optJSONObject(i);
        if (action == null) continue;
        String kind = ConversationContent.string(action, "type", "").toLowerCase(Locale.ROOT);
        String path = ConversationContent.string(action, "path", "");
        String query = ConversationContent.string(action, "query", "");
        if (kind.equals("read")) {
          addSource(path.isEmpty() ? ConversationContent.string(action, "name", "") : path, "", "file", "", turn, turnId);
          described = true;
        } else if (kind.equals("listfiles")) {
          addSource(path, "列出目录", "file", "", turn, turnId);
          described = true;
        } else if (kind.equals("search")) {
          addSource(query, path, "search", "", turn, turnId);
          described = true;
        }
      }
      parseArguments(item, turn, turnId);
      if (!described) commandPaths(item, turn, turnId);
      return;
    }
    parseArguments(item, turn, turnId);
  }

  /** Shell input names its files too; label them as command reads instead of guessing. */
  private void commandPaths(JSONObject item, int turn, String turnId) {
    Object raw = item.opt("command");
    String script = null;
    if (raw instanceof JSONArray) {
      StringBuilder joined = new StringBuilder();
      JSONArray array = (JSONArray) raw;
      for (int i = 0; i < array.length(); i++) {
        if (joined.length() > 0) joined.append(' ');
        joined.append(array.optString(i, ""));
      }
      script = joined.toString();
    } else if (raw instanceof String) {
      script = (String) raw;
    } else return;
    script = script.trim();
    while (script.matches("^(?:\\S*/)?(?:bash|sh|zsh|dash|fish)\\s+-l?c\\s+.*")) {
      script = script.replaceFirst("^(?:\\S*/)?(?:bash|sh|zsh|dash|fish)\\s+-l?c\\s+", "").trim();
      if (script.length() > 1 && (script.startsWith("\"") || script.startsWith("'"))) {
        char quote = script.charAt(0);
        int close = script.lastIndexOf(quote);
        script = close > 0 ? script.substring(1, close) : script.substring(1);
      }
    }
    int added = 0;
    for (String line : script.split("(?:&&|\\|\\||;|\\||\\n)")) {
      ArrayList<String> tokens = new ArrayList<>();
      for (String token : line.trim().split("[\\s]+")) if (!token.isEmpty()) tokens.add(token);
      if (tokens.isEmpty()) continue;
      String verb = lastSegment(tokens.get(0)).toLowerCase(Locale.ROOT);
      if (!verb.matches("cat|head|tail|less|more|bat|sed|awk|grep|rg|ag|ack|find|ls|tree|wc|diff|file|stat|readlink|realpath|xxd|hexdump|strings")) continue;
      for (int i = 1; i < tokens.size() && added < 4; i++) {
        String token = tokens.get(i);
        if (token.isEmpty() || token.startsWith("-") || token.contains("://")) continue;
        token = token.replaceAll("^[\"']|[\"';,)]$", "");
        if (!pathLike(token) || ignorePath(token)) continue;
        addSource(token, "命令读取 · " + verb, "file", "", turn, turnId);
        ++added;
      }
    }
  }

  private static boolean pathLike(String token) {
    if (token.length() > 240) return false;
    for (char bad : new char[] {'*', '$', '|', '>', '<', '=', '[', ']', '(', ')', '{', '}', '^', '+', '?'}) {
      if (token.indexOf(bad) >= 0) return false;
    }
    if (token.indexOf('/') >= 0) return true;
    return token.matches("(?i).+\\.(java|kt|kts|py|js|mjs|cjs|ts|tsx|jsx|go|rs|c|cc|cpp|cxx|h|hpp|cs|rb|php|swift|m|mm|sh|bash|zsh|sql|json|jsonl|yaml|yml|toml|xml|md|txt|gradle|properties|ini|cfg|conf|html|css|scss|sass|vue|svelte|lua|pl|pm|r|dart|scala|clj|ex|exs|erl|hrl|proto|graphql|gql|lock)");
  }

  private static boolean ignorePath(String path) {
    String value = path.toLowerCase(Locale.ROOT);
    return value.contains("/.git/") || value.startsWith(".git/") || value.contains("node_modules/")
        || value.contains("/build/") || value.contains("/.gradle/") || value.contains("__pycache__/")
        || value.contains("/.cache/") || value.contains("/proc/") || value.contains("/sys/");
  }

  private void web(JSONObject item, int turn, String turnId) {
    JSONObject action = item.optJSONObject("action");
    String query = action == null ? "" : ConversationContent.string(action, "query", "");
    JSONArray queries = action == null ? null : action.optJSONArray("queries");
    if (query.isEmpty() && queries != null) {
      for (int i = 0; i < queries.length(); i++) {
        String value = queries.optString(i, "");
        if (!value.isEmpty()) {
          addSource(value, "网页搜索", "search", "", turn, turnId);
        }
      }
    }
    if (!query.isEmpty()) addSource(query, "网页搜索", "search", "", turn, turnId);
    String url = action == null ? "" : ConversationContent.string(action, "url", "");
    String pattern = action == null ? "" : ConversationContent.string(action, "pattern", "");
    if (!url.isEmpty()) addSource(url, pattern.isEmpty() ? "打开网页" : "页内查找 · " + pattern, "web", "", turn, turnId);
    if (action == null) {
      String fallback = ConversationContent.string(item, "query", "");
      if (!fallback.isEmpty()) addSource(fallback, "网页搜索", "search", "", turn, turnId);
    }
  }

  /** Structured tool arguments are the only source for tools without commandActions. */
  private void parseArguments(JSONObject item, int turn, String turnId) {
    JSONObject args = arguments(item);
    if (args == null) return;
    String tool = ConversationContent.string(item, "tool", "").toLowerCase(Locale.ROOT);
    String server = ConversationContent.string(item, "server", "");
    boolean read = tool.matches("read|read_file|readfile|notebookread|view|open_file|cat");
    boolean search = tool.matches("grep|glob|search|find|ls|list|list_dir|listdir|rg|websearch|web_search|webfetch|web_fetch|fetch");
    boolean write = tool.matches("write|write_file|edit|multiedit|apply_patch|patch|notebookedit|create|delete|remove|move|rename");
    if (write) return;
    String path = first(args, "file_path", "filePath", "notebook_path", "target_file", "path");
    String directory = first(args, "directory", "dir", "cwd");
    String query = first(args, "query", "q", "pattern", "regex", "glob");
    String url = first(args, "url", "uri");
    String label = server.isEmpty() ? "" : server + " · ";
    if (!url.isEmpty()) addSource(url, label + tool, "web", "", turn, turnId);
    if (read && !path.isEmpty()) addSource(path, "", "file", "", turn, turnId);
    else if (search) {
      if (!query.isEmpty()) addSource(query, join(label, path), "search", "", turn, turnId);
      if (!directory.isEmpty()) addSource(directory, join(label, "搜索范围"), "file", "", turn, turnId);
    } else if (!path.isEmpty() && (tool.isEmpty() || tool.indexOf("read") >= 0 || tool.indexOf("list") >= 0)) {
      addSource(path, label + tool, "file", "", turn, turnId);
    }
  }

  private static JSONObject arguments(JSONObject item) {
    Object value = item.opt("arguments");
    if (value instanceof JSONObject) return (JSONObject) value;
    if (value instanceof String) {
      try {
        return new JSONObject((String) value);
      } catch (Exception ignored) {
      }
    }
    return null;
  }

  private static String first(JSONObject object, String... keys) {
    for (String key : keys) {
      String value = ConversationContent.string(object, key, "");
      if (!value.isEmpty()) return value;
    }
    return "";
  }

  private void addSource(String label, String detail, String kind, String note, int turn, String turnId) {
    label = label == null ? "" : label.trim();
    if (label.isEmpty()) return;
    String key = kind + "\u0000" + label;
    Source source = sourceIndex.get(key);
    if (source == null) {
      source = new Source();
      source.label = label;
      source.kind = kind;
      source.turn = turn;
      source.turnId = turnId;
      sourceIndex.put(key, source);
      sources.add(source);
    } else {
      ++source.occurrences;
      source.turn = turn;
      source.turnId = turnId;
    }
    source.detail = detail == null ? "" : detail;
    if (!note.isEmpty() && source.detail.isEmpty()) source.detail = note;
  }

  private void finish() {
    for (File file : fileIndex.values()) {
      files.add(file);
      if (file.kind.equals("created")) ++created;
      else if (file.kind.equals("deleted")) ++deleted;
      else ++modified;
    }
    files.sort((left, right) -> {
      int kind = Integer.compare(kindOrder(left.kind), kindOrder(right.kind));
      return kind != 0 ? kind : left.path.compareToIgnoreCase(right.path);
    });
    for (Subagent child : subagents) {
      if (child.status.isEmpty()) child.status = "completed";
      if (child.title.isEmpty()) child.title = (child.engine.isEmpty() ? "" : NativeUi.engineName(child.engine)) + " 子代理";
      if (child.durationMs == 0 && child.startedAtMs > 0 && child.finishedAtMs >= child.startedAtMs) {
        child.durationMs = child.finishedAtMs - child.startedAtMs;
      }
      if (child.active()) ++activeSubagents;
    }
    subagents.sort((left, right) -> {
      int active = Boolean.compare(right.active(), left.active());
      return active != 0 ? active : Integer.compare(right.turn, left.turn);
    });
    if (sources.size() > 400) {
      sources.subList(400, sources.size()).clear();
    }
  }

  private static int kindOrder(String kind) {
    return kind.equals("created") ? 0 : kind.equals("modified") ? 1 : kind.equals("deleted") ? 2 : 3;
  }

  /** Stable group key for a live child event; null means it belongs to the parent. */
  static String childKey(JSONObject item, String turnEngine) {
    String type = ConversationContent.string(item, "type", "");
    if (type.equals("userMessage") || type.equals("agentMessage") || type.equals("collabAgentToolCall")) return null;
    String engine = ConversationContent.string(item, "sourceEngine", turnEngine);
    String thread = ConversationContent.string(item, "sourceThreadId", "");
    if (!thread.isEmpty()) return engine + ":" + thread;
    if (type.equals("subAgentActivity")) {
      String id = ConversationContent.string(item, "agentThreadId", "");
      return id.isEmpty() ? null : engine + ":" + id;
    }
    String parent = ConversationContent.string(item, "parentToolUseId", "");
    return parent.isEmpty() ? null : "tool:" + engine + ":" + parent;
  }

  /** Cheap change marker; the panel repaints only when the visible inventory moved. */
  String signature() {
    StringBuilder value = new StringBuilder();
    value.append(turns).append('/').append(files.size()).append('/').append(subagents.size())
        .append('/').append(sources.size()).append('/').append(created).append('/').append(modified)
        .append('/').append(deleted).append('/').append(activeSubagents).append('/').append(durationMs)
        .append('/').append(tokens);
    for (File file : files) value.append(file.kind.charAt(0)).append(file.turn).append(file.occurrences).append(';');
    for (Subagent child : subagents) {
      value.append(child.key).append(child.status).append(child.tools).append(child.messages)
          .append(child.reasoning).append(child.log.size()).append(child.preview.length()).append(';');
    }
    for (Source source : sources) value.append(source.kind).append(source.turn).append(source.occurrences).append(';');
    return Integer.toHexString(value.toString().hashCode()) + "-" + files.size() + "-" + subagents.size();
  }

  Subagent child(String key) { return childIndex.get(key); }

  static boolean active(String status) {
    return status != null && status.matches("inProgress|in_progress|pending|running|starting|executing|waiting_input|stopping");
  }

  static String normalizeStatus(String status) {
    if (status == null) return "pending";
    String value = status.toLowerCase(Locale.ROOT);
    if (value.equals("completed") || value.equals("succeeded") || value.equals("success")) return "completed";
    if (value.equals("failed") || value.equals("error") || value.equals("errored")) return "failed";
    if (value.equals("interrupted") || value.equals("cancelled") || value.equals("canceled") || value.equals("stopped") || value.equals("aborted")) return "interrupted";
    if (value.equals("discarded")) return "discarded";
    if (value.equals("pending") || value.equals("pendinginit") || value.equals("queued")) return "pending";
    return "inProgress";
  }

  static String statusLabel(String status) {
    if (status == null) return "";
    if (status.equals("completed")) return "已完成";
    if (status.equals("failed")) return "失败";
    if (status.equals("interrupted")) return "已停止";
    if (status.equals("discarded")) return "未执行";
    if (status.equals("pending")) return "等待中";
    return "进行中";
  }

  static String duration(long millis) {
    if (millis <= 0) return "";
    if (millis < 1000) return "<1 秒";
    long seconds = Math.round(millis / 1000d);
    if (seconds < 60) return seconds + " 秒";
    long minutes = seconds / 60, rest = seconds % 60;
    if (minutes < 60) return minutes + " 分" + (rest > 0 ? " " + rest + " 秒" : "");
    return (minutes / 60) + " 小时 " + (minutes % 60) + " 分";
  }

  static String tokens(long value) {
    if (value <= 0) return "";
    if (value >= 1000000) return String.format(Locale.ROOT, "%.1fM", value / 1000000d);
    if (value >= 1000) return String.format(Locale.ROOT, "%.1fk", value / 1000d);
    return Long.toString(value);
  }

  private static String titleFromPrompt(String prompt) {
    String line = oneLine(prompt, 80);
    return line.isEmpty() ? "" : line;
  }

  private static String lastSegment(String path) {
    String value = path.replace('\\', '/');
    int cut = value.lastIndexOf('/');
    return cut >= 0 ? value.substring(cut + 1) : value;
  }

  private static String activityName(String kind) {
    if (kind.equals("started")) return " · 已启动";
    if (kind.equals("interacted")) return " · 交互";
    if (kind.equals("interrupted")) return " · 已中断";
    if (kind.equals("completed")) return " · 已完成";
    return "";
  }

  private static String join(String left, String right) {
    if (left == null) left = "";
    if (right == null || right.isEmpty()) return left;
    return left.isEmpty() ? right : left + " · " + right;
  }

  static String oneLine(String value, int limit) {
    if (value == null) return "";
    String text = value.replace('\r', '\n').replaceAll("\\s+", " ").trim();
    if (text.length() > limit) text = text.substring(0, Math.max(0, limit - 1)) + "…";
    return text;
  }
}
