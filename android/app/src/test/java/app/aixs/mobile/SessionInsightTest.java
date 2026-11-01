package app.aixs.mobile;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionInsightTest {
  private static JSONObject thread(JSONObject... turns) throws Exception {
    JSONObject value = new JSONObject();
    value.put("cwd", "/root/project");
    value.put("engine", "codex");
    value.put("model", "gpt-test");
    JSONArray list = new JSONArray();
    for (JSONObject turn : turns) list.put(turn);
    value.put("turns", list);
    return value;
  }

  private static JSONObject turn(String id, String status, JSONObject... items) throws Exception {
    JSONObject value = new JSONObject();
    value.put("id", id);
    value.put("status", status);
    JSONArray list = new JSONArray();
    for (JSONObject item : items) list.put(item);
    value.put("items", list);
    return value;
  }

  private static JSONObject item(String id, String type) throws Exception {
    return new JSONObject().put("id", id).put("type", type);
  }

  @Test public void fileReportWinsOverRawFileChangeAndMergesRepeatedTurns() throws Exception {
    JSONObject report = item("report", "fileReport").put("changes", new JSONArray()
        .put(new JSONObject().put("path", "src/New.java").put("kind", "created").put("verification", "content"))
        .put(new JSONObject().put("path", "src/New.java").put("kind", "created").put("verification", "content"))
        .put(new JSONObject().put("path", "README.md").put("kind", "modified")));
    JSONObject change = item("change", "fileChange").put("changes",
        new JSONArray().put(new JSONObject().put("path", "generated.log").put("kind", "add")));
    SessionInsight insight = SessionInsight.of(thread(
        turn("t1", "completed", report, change),
        turn("t2", "completed", item("report2", "fileReport").put("changes",
            new JSONArray().put(new JSONObject().put("path", "README.md").put("kind", "modified"))))));
    assertEquals(2, insight.files.size());
    assertEquals(1, insight.created);
    assertEquals(1, insight.modified);
    assertEquals(0, insight.deleted);
    SessionInsight.File readme = insight.files.get(1);
    assertEquals("README.md", readme.path);
    assertEquals(2, readme.occurrences);
    for (SessionInsight.File file : insight.files) assertNotEquals("generated.log", file.path);
  }

  @Test public void collaborationFilesStayHiddenUntilTheWorkflowStops() throws Exception {
    JSONObject report = item("report", "fileReport").put("root", "/work/a").put("participant", "Alice")
        .put("changes", new JSONArray().put(new JSONObject().put("path", "out.txt").put("kind", "created")));
    JSONObject running = turn("t1", "completed", report).put("workflowId", "wf").put("collaborationStatus", "running");
    assertTrue(SessionInsight.of(thread(running)).files.isEmpty());
    running.put("collaborationStatus", "completed");
    SessionInsight done = SessionInsight.of(thread(running));
    assertEquals(1, done.files.size());
    assertEquals("/work/a", done.files.get(0).root);
    assertTrue(done.files.get(0).members.contains("Alice"));
  }

  @Test public void collaborationWorkspacesKeepSameRelativePathSeparate() throws Exception {
    JSONObject a = item("a", "fileReport").put("root", "/work/a").put("participant", "Alice")
        .put("changes", new JSONArray().put(new JSONObject().put("path", "out.txt").put("kind", "created")));
    JSONObject b = item("b", "fileReport").put("root", "/work/b").put("participant", "Bob")
        .put("changes", new JSONArray().put(new JSONObject().put("path", "out.txt").put("kind", "created")));
    JSONObject turn = turn("t1", "completed", a, b).put("workflowId", "wf").put("collaborationStatus", "completed");
    SessionInsight insight = SessionInsight.of(thread(turn));
    assertEquals(2, insight.files.size());
    assertEquals("/work/a", insight.files.get(0).root);
    assertEquals("/work/b", insight.files.get(1).root);
    assertEquals(1, insight.files.get(0).members.size());
    assertEquals(1, insight.files.get(1).members.size());
  }

  @Test public void rawFileChangeIsUsedWhenNoReportExists() throws Exception {
    JSONObject change = item("change", "fileChange").put("changes", new JSONArray()
        .put(new JSONObject().put("path", "notes/todo.md").put("kind", new JSONObject().put("type", "add"))));
    SessionInsight insight = SessionInsight.of(thread(turn("t1", "completed", change)));
    assertEquals(1, insight.files.size());
    assertEquals("created", insight.files.get(0).kind);
    assertEquals("agent", insight.files.get(0).verification);
  }

  @Test public void sourcesComeFromReportedToolArgumentsAndCitationsOnly() throws Exception {
    JSONObject commands = item("c", "commandExecution").put("command", "grep -r needle src").put("commandActions",
        new JSONArray().put(new JSONObject().put("type", "read").put("path", "/root/project/A.java"))
            .put(new JSONObject().put("type", "listFiles").put("path", "src"))
            .put(new JSONObject().put("type", "search").put("query", "needle").put("path", "src")));
    JSONObject citation = item("a", "agentMessage").put("content", "answer").put("memoryCitation",
        new JSONObject().put("entries", new JSONArray().put(new JSONObject()
            .put("path", "docs/spec.md").put("lineStart", 3).put("lineEnd", 5).put("note", "约束"))));
    JSONObject grep = item("g", "dynamicToolCall").put("tool", "Grep")
        .put("arguments", new JSONObject().put("pattern", "TODO").put("path", "src"));
    JSONObject write = item("w", "dynamicToolCall").put("tool", "Write")
        .put("arguments", new JSONObject().put("file_path", "should-not-appear.txt"));
    JSONObject search = item("s", "webSearch").put("action",
        new JSONObject().put("type", "search").put("query", "codex source"));
    JSONObject page = item("p", "webSearch").put("action",
        new JSONObject().put("type", "openPage").put("url", "https://example.com/codex"));
    SessionInsight insight = SessionInsight.of(thread(turn("t1", "completed", commands, citation, grep, write, search, page)));
    java.util.LinkedHashMap<String, SessionInsight.Source> found = new java.util.LinkedHashMap<>();
    for (SessionInsight.Source source : insight.sources) found.put(source.label, source);
    assertTrue(found.containsKey("/root/project/A.java"));
    assertEquals("file", found.get("/root/project/A.java").kind);
    assertTrue(found.containsKey("src"));
    assertTrue(found.containsKey("needle"));
    assertEquals("search", found.get("needle").kind);
    assertTrue(found.containsKey("docs/spec.md"));
    assertTrue(found.get("docs/spec.md").detail.contains("第 3-5 行"));
    assertTrue(found.containsKey("TODO"));
    assertEquals("search", found.get("TODO").kind);
    assertTrue(found.containsKey("codex source"));
    assertTrue(found.containsKey("https://example.com/codex"));
    assertEquals("web", found.get("https://example.com/codex").kind);
    assertFalse(found.containsKey("should-not-appear.txt"));
  }

  @Test public void shellCommandsContributeReadableFileSources() throws Exception {
    JSONObject shell = item("c", "commandExecution")
        .put("command", "/bin/bash -lc \"cat src/Main.java; grep TODO src/App.java; cat node_modules/x.js\"")
        .put("commandActions", new JSONArray().put(new JSONObject().put("type", "unknown")
            .put("command", "cat src/Main.java")));
    SessionInsight insight = SessionInsight.of(thread(turn("t1", "completed", shell)));
    java.util.LinkedHashMap<String, SessionInsight.Source> found = new java.util.LinkedHashMap<>();
    for (SessionInsight.Source source : insight.sources) found.put(source.label, source);
    assertTrue(found.containsKey("src/Main.java"));
    assertEquals("file", found.get("src/Main.java").kind);
    assertTrue(found.get("src/Main.java").detail.contains("命令读取"));
    assertTrue(found.containsKey("src/App.java"));
    assertFalse(found.containsKey("node_modules/x.js"));
  }

  @Test public void nativeChildrenAndLiveItemsShareOneRow() throws Exception {
    JSONObject saved = new JSONObject();
    saved.put("claude:child-1", new JSONObject()
        .put("engine", "claude").put("model", "haiku")
        .put("sourceThreadId", "child-1").put("sourceTurnId", "t0")
        .put("stats", new JSONObject().put("toolCalls", 2).put("assistantMessages", 1)
            .put("turns", 1).put("durationMs", 4800).put("statuses", new JSONObject().put("completed", 1)))
        .put("turn", new JSONObject().put("status", "completed")
            .put("startedAt", "2026-01-01T00:00:00Z").put("completedAt", "2026-01-01T00:00:04.800Z")));
    JSONObject message = item("m", "subagentMessage").put("parentToolUseId", "toolu-1")
        .put("sourceThreadId", "child-1").put("sourceEngine", "claude").put("sourceModel", "haiku")
        .put("status", "completed").put("text", "done");
    JSONObject progress = item("p", "taskProgress").put("parentToolUseId", "toolu-2")
        .put("sourceEngine", "dsh").put("status", "inProgress");
    SessionInsight insight = SessionInsight.of(thread(
        turn("t1", "completed", message).put("nativeChildren", saved),
        turn("t2", "inProgress", progress)));
    assertEquals(2, insight.subagents.size());
    SessionInsight.Subagent claude = insight.child("claude:child-1");
    assertNotNull(claude);
    assertEquals("completed", claude.status);
    assertEquals(2, claude.tools);
    assertEquals(1, claude.messages);
    assertEquals(4800, claude.durationMs);
    assertEquals("done", claude.preview);
    SessionInsight.Subagent live = insight.child("tool:dsh:toolu-2");
    assertNotNull(live);
    assertEquals("inProgress", live.status);
    assertTrue(live.active());
    assertEquals(1, insight.activeSubagents);
  }

  @Test public void collabReceiverStatesBecomeSubagentsWithoutDuplicates() throws Exception {
    JSONObject spawn = item("spawn", "collabAgentToolCall").put("tool", "spawnAgent")
        .put("prompt", "拆分并验证登录流程").put("model", "gpt-test")
        .put("receiverThreadIds", new JSONArray().put("uuid-1").put("uuid-2"))
        .put("agentsStates", new JSONObject()
            .put("uuid-1", new JSONObject().put("status", "completed").put("message", "完成"))
            .put("uuid-2", new JSONObject().put("status", "inProgress")));
    SessionInsight insight = SessionInsight.of(thread(turn("t1", "inProgress", spawn)));
    assertEquals(2, insight.subagents.size());
    SessionInsight.Subagent first = insight.child("codex:uuid-1");
    assertNotNull(first);
    assertEquals("completed", first.status);
    assertEquals("拆分并验证登录流程", first.title);
    assertEquals("完成", first.preview);
    assertEquals("inProgress", insight.child("codex:uuid-2").status);
    assertEquals(1, insight.activeSubagents);
  }

  @Test public void childGroupKeysOnlyJoinTheSameChild() throws Exception {
    JSONObject parent = item("p", "commandExecution");
    JSONObject childA = item("a", "reasoning").put("parentToolUseId", "toolu-a").put("sourceEngine", "claude");
    JSONObject childA2 = item("a2", "dynamicToolCall").put("parentToolUseId", "toolu-a").put("sourceEngine", "claude");
    JSONObject childB = item("b", "subagentMessage").put("sourceThreadId", "thread-b").put("sourceEngine", "codex");
    JSONObject childB2 = item("b2", "subAgentActivity").put("agentThreadId", "thread-b").put("sourceEngine", "codex");
    assertNull(SessionInsight.childKey(parent, "codex"));
    assertEquals("tool:claude:toolu-a", SessionInsight.childKey(childA, "codex"));
    assertEquals(SessionInsight.childKey(childA, "codex"), SessionInsight.childKey(childA2, "codex"));
    assertEquals("codex:thread-b", SessionInsight.childKey(childB, "codex"));
    assertEquals(SessionInsight.childKey(childB, "codex"), SessionInsight.childKey(childB2, "codex"));
    assertNull(SessionInsight.childKey(item("u", "userMessage"), "codex"));
  }

  @Test public void signatureMovesWhenChildStatusOrFilesChange() throws Exception {
    JSONObject first = thread(turn("t1", "inProgress", item("p", "taskProgress")
        .put("parentToolUseId", "toolu").put("sourceEngine", "claude").put("status", "inProgress")));
    SessionInsight live = SessionInsight.of(first);
    String before = live.signature();
    first.getJSONArray("turns").getJSONObject(0).getJSONArray("items").getJSONObject(0).put("status", "completed");
    String after = SessionInsight.of(first).signature();
    assertNotEquals(before, after);
  }
}
