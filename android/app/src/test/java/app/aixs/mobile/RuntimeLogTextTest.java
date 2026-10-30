package app.aixs.mobile;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class RuntimeLogTextTest {
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
