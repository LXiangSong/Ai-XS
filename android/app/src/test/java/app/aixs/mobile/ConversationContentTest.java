package app.aixs.mobile;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConversationContentTest {
  @Test public void completedTurnWithNullErrorDoesNotRenderAnError() throws Exception {
    assertEquals("", ConversationContent.error(new JSONObject("{\"status\":\"completed\",\"error\":null}")));
    assertEquals("", ConversationContent.error(new JSONObject()));
    assertEquals("Request failed", ConversationContent.error(new JSONObject("{\"error\":{\"message\":\"Request failed\"}}")));
  }
  @Test public void unnamedThreadUsesPreviewInsteadOfJsonNull() throws Exception {
    assertEquals("Review this project", ConversationContent.title(new JSONObject("{\"name\":null,\"preview\":\"Review this project\"}")));
    assertEquals("新对话", ConversationContent.title(new JSONObject("{\"name\":null,\"preview\":null}")));
    assertEquals("Renamed chat", ConversationContent.title(new JSONObject("{\"name\":\"Renamed chat\"}")));
  }
  @Test public void nullableModelAndDirectoryKeepTheExistingSelection() throws Exception {
    JSONObject thread = new JSONObject("{\"model\":null,\"cwd\":null}");
    assertEquals("configured-model", ConversationContent.string(thread,"model","configured-model"));
    assertEquals("/root/projects", ConversationContent.string(thread,"cwd","/root/projects"));
  }
  @Test public void literalNullInUserAndAgentMessagesRemainsVisible() throws Exception {
    assertEquals("null", ConversationContent.item(new JSONObject("{\"type\":\"userMessage\",\"content\":[{\"type\":\"text\",\"text\":\"null\"}]}")));
    assertEquals("null", ConversationContent.item(new JSONObject("{\"type\":\"agentMessage\",\"text\":\"null\"}")));
    assertEquals("", ConversationContent.item(new JSONObject("{\"type\":\"agentMessage\",\"text\":null}")));
  }
  @Test public void reasoningAndToolOutputAreReadableWhenExpanded() throws Exception {
    assertEquals("Plan\n\nInspect files", ConversationContent.item(new JSONObject("{\"type\":\"reasoning\",\"summary\":[\"Plan\"],\"content\":[\"Inspect files\"]}")));
    assertEquals("pwd\n/root/projects", ConversationContent.item(new JSONObject("{\"type\":\"commandExecution\",\"command\":\"pwd\",\"aggregatedOutput\":\"/root/projects\",\"result\":null}")));
  }
  @Test public void quotingPreservesDraftAndMultilineContent() {
    assertEquals("草稿\n\n> 第一行\n> \n> 第三行\n\n", ConversationContent.quote("草稿", "第一行\r\n\r\n第三行"));
    assertEquals("> reply\n\n", ConversationContent.quote("", "reply"));
  }
  @Test public void editingExtractsTextWithoutConvertingAttachmentsToText() throws Exception {
    JSONObject item = new JSONObject("{\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"image\",\"url\":\"/photo.png\"},{\"type\":\"text\",\"text\":\"b\"}]}");
    assertEquals("a\nb", ConversationContent.editableText(item));
  }
  @Test public void statsOnlyIncludeTurnsInTheCurrentConversationAndSurviveMissingCache() throws Exception {
    JSONObject thread = new JSONObject("{\"turns\":[{\"id\":\"codex:a:1\",\"durationMs\":30},{\"id\":\"claude:b:1\",\"durationMs\":40}]}");
    JSONObject saved = new JSONObject("{\"codex:a:1\":{\"ms\":25,\"tokens\":100},\"another-conversation\":{\"ms\":900,\"tokens\":9999}}");
    assertEquals(65, ConversationContent.sumTurnStat(thread, saved, "ms", "durationMs"));
    assertEquals(100, ConversationContent.sumTurnStat(thread, saved, "tokens", "billedTokens"));
    assertEquals(70, ConversationContent.sumTurnStat(thread, new JSONObject(), "ms", "durationMs"));
    assertEquals(0, ConversationContent.sumTurnStat(null, saved, "tokens", "billedTokens"));
  }
}
