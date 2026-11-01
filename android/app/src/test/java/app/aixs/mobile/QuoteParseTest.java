package app.aixs.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** 发送后的引用消息必须还原成“引用卡 + 自己说的话”，而不是把原始 markdown 画进气泡。 */
public class QuoteParseTest {
  @Test public void splitsSentQuoteIntoCardAndWords() {
    String value = "> 引用 DeepSeek：\n> Confirmed: both markers printed,\n> 8 seconds apart.\n\n再解释一下";
    ConversationRenderer.Quoted quoted = ConversationRenderer.splitQuote(value);
    assertNotNull(quoted);
    assertEquals("DeepSeek", quoted.sender);
    assertEquals("Confirmed: both markers printed,\n8 seconds apart.", quoted.body);
    assertEquals("再解释一下", quoted.rest);
  }

  @Test public void quoteWithoutSenderStillRenders() {
    ConversationRenderer.Quoted quoted = ConversationRenderer.splitQuote("> 引用：\n> 原文一行\n\n问题");
    assertNotNull(quoted);
    assertEquals("", quoted.sender);
    assertEquals("原文一行", quoted.body);
    assertEquals("问题", quoted.rest);
  }

  @Test public void quoteWithoutDraftKeepsEmptyRest() {
    ConversationRenderer.Quoted quoted = ConversationRenderer.splitQuote("> 引用 我：\n> 只有引用\n\n");
    assertNotNull(quoted);
    assertEquals("我", quoted.sender);
    assertEquals("只有引用", quoted.body);
    assertEquals("", quoted.rest);
  }

  @Test public void plainTextIsNotAQuote() {
    assertNull(ConversationRenderer.splitQuote("普通消息，没有任何引用"));
  }

  @Test public void genericBlockquoteIsNotOurQuote() {
    assertNull(ConversationRenderer.splitQuote("> 这是用户自己写的引用块\n> 第二行"));
  }

  @Test public void quoteWithoutBodyIsNotAQuote() {
    assertNull(ConversationRenderer.splitQuote("> 引用 DeepSeek：\n\n正文"));
  }
}
