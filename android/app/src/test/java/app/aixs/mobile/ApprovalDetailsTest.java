package app.aixs.mobile;

import static org.junit.Assert.*;
import java.util.List;
import org.json.*;
import org.junit.Test;

public class ApprovalDetailsTest {
  private String text(JSONObject params) {
    StringBuilder text = new StringBuilder();
    for (ApprovalDetails.Section row : ApprovalDetails.sections(params)) text.append(row.title).append(": ").append(row.text).append('\n');
    return text.toString();
  }
  @Test public void claudeReadShowsFileAndCwdInsteadOfOnlyRead() throws Exception {
    String result = text(new JSONObject().put("engine", "claude").put("tool", "Read").put("command", "Read")
        .put("arguments", new JSONObject().put("file_path", "/project/SKILL.md").put("limit", 20))
        .put("reason", "查看技能说明").put("cwd", "/project"));
    for (String value : new String[] {"Claude Code", "Read", "/project/SKILL.md", "20", "查看技能说明", "工作目录"})
      assertTrue(result, result.contains(value));
    assertFalse(result.contains("命令 / 操作: Read"));
  }
  @Test public void bashCommandDoesNotHideReasonAndEditKeepsBothSides() throws Exception {
    String command = "printf 'permission-check'";
    String result = text(new JSONObject().put("tool", "Bash").put("command", command).put("cwd", "/tmp")
        .put("reason", "验证命令").put("arguments", new JSONObject().put("command", command).put("timeout", 1000)));
    assertTrue(result.contains(command)); assertTrue(result.contains("验证命令")); assertTrue(result.contains("1000"));
    result = text(new JSONObject().put("tool", "Edit").put("arguments", new JSONObject().put("file_path", "/tmp/demo")
        .put("old_string", "before").put("new_string", "after")));
    assertTrue(result.contains("/tmp/demo")); assertTrue(result.contains("before")); assertTrue(result.contains("after"));
  }
  @Test public void otherAdaptersAndUnknownShapesKeepReviewableDetails() throws Exception {
    assertTrue(text(new JSONObject().put("engine", "deepseek").put("rawInput", new JSONObject().put("path", "/repo/test")))
        .contains("/repo/test"));
    String result = text(new JSONObject().put("command", new JSONArray().put("sh").put("-c").put("echo test"))
        .put("reason", "需要批准").put("fileChanges", new JSONObject().put("/repo/a", "patch content")));
    for (String value : new String[] {"echo test", "需要批准", "patch content", "/repo/a"}) assertTrue(result.contains(value));
    assertTrue(text(new JSONObject()).contains("未提供操作参数"));
    assertFalse(text(new JSONObject().put("reason", JSONObject.NULL)).contains("null"));
  }
}
