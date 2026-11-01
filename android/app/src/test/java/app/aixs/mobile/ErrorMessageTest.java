package app.aixs.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class ErrorMessageTest {
  @Test public void unloadedSubagentErrorIsActionableChinese() {
    String raw = "cannot resume an unloaded multi-agent v2 sub-agent through its parent; resume the parent first, or use a new turn";
    String text = ErrorMessage.friendly(raw);
    assertTrue(text.contains("子代理"));
    assertTrue(text.contains("父会话"));
    assertFalse(text.contains("cannot"));
    assertFalse(text.contains("resume"));
  }

  @Test public void commonEngineFailuresStayChinese() {
    assertTrue(ErrorMessage.friendly("stream disconnected before completion").contains("连接中断"));
    assertTrue(ErrorMessage.friendly("Request timed out").contains("超时"));
    assertTrue(ErrorMessage.friendly("401 Unauthorized").contains("鉴权"));
    assertTrue(ErrorMessage.friendly("429 too many requests").contains("频繁"));
    assertTrue(ErrorMessage.friendly("context length exceeded").contains("上下文"));
    assertTrue(ErrorMessage.friendly("thread not found").contains("会话"));
  }

  @Test public void chineseMessagesAreKeptVerbatim() {
    String value = "找不到这个会话，请重新打开。";
    assertEquals(value, ErrorMessage.friendly(value));
  }

  @Test public void unknownEnglishFallsBackToChineseWithoutLeakingRawText() {
    String text = ErrorMessage.friendly("something exploded in the engine at /very/secret/path");
    assertTrue(text.startsWith("操作失败"));
    assertFalse(text.contains("exploded"));
    assertFalse(text.contains("secret"));
  }

  @Test public void blankMessageStillReadsInChinese() {
    assertEquals("操作失败，请稍后重试。", ErrorMessage.friendly(" "));
    assertEquals("操作失败，请稍后重试。", ErrorMessage.friendly(null));
  }
}
