package app.aixs.mobile;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class RuntimeLogTextTest {
  private String event(String level, String source, String operation, String message) throws Exception {
    return new org.json.JSONObject().put("time", 1720000000000L).put("level", level).put("source", source)
        .put("operation", operation).put("version", "0.8.6-alpha").put("message", message) + "\n";
  }
  @Test public void structuredSeverityDoesNotGuessFromMessageKeywords() throws Exception {
    String input = event("INFO", "runtime", "1", "errorCount: 0; failure checks passed")
        + event("ERROR", "runtime", "1", "网络连接中断\n原因与堆栈")
        + event("WARN", "browser", "", "页面响应缓慢");
    String errors = RuntimeLogText.filter(input, "error", "");
    assertFalse(errors.contains("errorCount"));
    assertTrue(errors.contains("[ERROR] runtime · #1"));
    assertTrue(errors.contains("原因与堆栈"));
    assertFalse(errors.contains("页面响应缓慢"));
    assertTrue(RuntimeLogText.filter(input, "warn", "").contains("页面响应缓慢"));
  }
  @Test public void correlatesOperationsAndSourcesWithoutLosingMultilineErrors() throws Exception {
    String input = event("DEBUG", "runtime", "a12", "子进程输出")
        + event("ERROR", "runtime", "b34", "失败\n  at call")
        + event("ERROR", "browser", "", "页面失败") + "旧版失败\n";
    String scoped = RuntimeLogText.filter(input, "all", "", "all", "b34");
    assertTrue(scoped.contains("失败\n    at call"));
    assertFalse(scoped.contains("子进程输出"));
    assertFalse(scoped.contains("页面失败"));
    assertFalse(scoped.contains("旧版失败"));
    assertTrue(RuntimeLogText.filter(input, "error", "页面", "browser", "").contains("页面失败"));
    assertEquals("", RuntimeLogText.filter(input, "all", "不匹配", "runtime", ""));
  }
  @Test public void snapshotAlignsChineseLinesAndReadsRotation() throws Exception {
    File file = File.createTempFile("aixs-log", ".txt");
    try {
      Files.write(file.toPath(), "旧内容一行\n警告：测试\n最后一行\n".getBytes(StandardCharsets.UTF_8));
      String value = RuntimeLogText.read(file, 31);
      assertFalse(value.contains("\ufffd"));
      assertEquals("警告：测试\n最后一行\n", value);
      Files.write(file.toPath(), "新文件\n".getBytes(StandardCharsets.UTF_8));
      assertEquals("新文件\n", RuntimeLogText.read(file, 31));
    } finally { file.delete(); }
  }

  @Test public void filtersMixedLanguageErrorsAndRemovesTerminalFormatting() throws Exception {
    File file = File.createTempFile("aixs-log", ".txt");
    try {
      Files.write(file.toPath(), "\u001b[31mERROR failed\u001b[0m\r警告：安装\n连接异常\n正常\n".getBytes(StandardCharsets.UTF_8));
      String value = RuntimeLogText.read(file, 1024);
      assertFalse(value.contains("\u001b"));
      assertEquals("ERROR failed\n连接异常\n", RuntimeLogText.filter(value, "error", ""));
      assertEquals("ERROR failed\n", RuntimeLogText.filter(value, "warn", "Error"));
      assertEquals(4, RuntimeLogText.lines(value));
      assertEquals(0, RuntimeLogText.lines(""));
    } finally { file.delete(); }
  }
}
