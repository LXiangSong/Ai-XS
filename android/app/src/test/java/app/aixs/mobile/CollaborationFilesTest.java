package app.aixs.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CollaborationFilesTest {
  private static JSONObject report(String participant, String engine, String model, JSONArray changes) throws Exception {
    return new JSONObject().put("type", "fileReport").put("participant", participant)
        .put("engine", engine).put("model", model).put("root", "/p").put("changes", changes);
  }

  private static JSONObject change(String path, String kind, int added, int removed) throws Exception {
    return new JSONObject().put("path", path).put("kind", kind).put("added", added).put("removed", removed);
  }

  @Test public void exclusiveFilesAreGroupedByMemberAndOverlapIsShared() throws Exception {
    JSONArray items = new JSONArray();
    items.put(report("Alice", "codex", "m1", new JSONArray().put(change("/p/a.txt", "created", 2, 0))
        .put(change("/p/shared.txt", "modified", 1, 1))));
    items.put(report("Bob", "claude", "m2", new JSONArray().put(change("/p/b.txt", "created", 3, 0))
        .put(change("/p/shared.txt", "modified", 1, 1))));
    CollaborationFiles.Report result = CollaborationFiles.of(items);
    assertEquals(3, result.total);
    assertEquals(6, result.added);
    assertEquals(1, result.removed);
    assertEquals(2, result.members.size());
    assertEquals("Alice", result.members.get(0).name);
    assertEquals(1, result.members.get(0).files.size());
    assertEquals("/p/a.txt", result.members.get(0).files.get(0).path);
    assertEquals(2, result.members.get(0).added);
    assertEquals("Bob", result.members.get(1).name);
    assertEquals("/p/b.txt", result.members.get(1).files.get(0).path);
    assertEquals(1, result.shared.size());
    assertEquals("/p/shared.txt", result.shared.get(0).path);
    assertTrue(result.shared.get(0).memberNames.contains("Alice"));
    assertTrue(result.shared.get(0).memberNames.contains("Bob"));
  }

  @Test public void sameRelativePathInDifferentWorkspacesIsNotShared() throws Exception {
    JSONArray items = new JSONArray();
    items.put(new JSONObject().put("type", "fileReport").put("participant", "Alice").put("root", "/work/a")
        .put("changes", new JSONArray().put(change("out.txt", "created", 1, 0))));
    items.put(new JSONObject().put("type", "fileReport").put("participant", "Bob").put("root", "/work/b")
        .put("changes", new JSONArray().put(change("out.txt", "created", 2, 0))));
    CollaborationFiles.Report result = CollaborationFiles.of(items);
    assertEquals(2, result.total);
    assertEquals(1, result.members.get(0).files.size());
    assertEquals(1, result.members.get(1).files.size());
    assertTrue(result.shared.isEmpty());
  }

  @Test public void aMemberWithOnlySharedFilesStillAppears() throws Exception {
    JSONArray items = new JSONArray();
    items.put(report("Alice", "codex", "m1", new JSONArray().put(change("/p/shared.txt", "modified", 1, 0))));
    items.put(report("Bob", "claude", "m2", new JSONArray().put(change("/p/shared.txt", "modified", 1, 0))));
    CollaborationFiles.Report result = CollaborationFiles.of(items);
    assertEquals(2, result.members.size());
    assertTrue(result.members.get(0).files.isEmpty());
    assertEquals(1, result.shared.size());
  }

  @Test public void emptyReportsHaveNothingToShow() throws Exception {
    CollaborationFiles.Report result = CollaborationFiles.of(new JSONArray());
    assertEquals(0, result.total);
    assertTrue(result.members.isEmpty());
    assertTrue(result.shared.isEmpty());
  }
}
