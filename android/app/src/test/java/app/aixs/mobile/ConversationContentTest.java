package app.aixs.mobile;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConversationContentTest {
  @Test public void queueEditRetainsTextAndImagesAddedBeforeTheDeleteReply() throws Exception {
    JSONArray queued = new JSONArray("[{\"type\":\"text\",\"text\":\"queued goal\"},{\"type\":\"localImage\",\"path\":\"/old.png\"}]");
    JSONArray attachments = new JSONArray("[{\"type\":\"localImage\",\"path\":\"/new.png\"}]");
    JSONArray result = ConversationContent.restoredInput(queued, "new unsent information", attachments);
    assertEquals(4, result.length());
    assertEquals("queued goal", result.getJSONObject(0).getString("text"));
    assertEquals("/old.png", result.getJSONObject(1).getString("path"));
    assertEquals("new unsent information", result.getJSONObject(2).getString("text"));
    assertEquals("/new.png", result.getJSONObject(3).getString("path"));
    assertEquals(2, queued.length());assertEquals(1, attachments.length());
  }
  @Test public void queueEditIntoAnEmptyComposerKeepsAttachmentOnlyMessages() throws Exception {
    JSONArray queued = new JSONArray("[{\"type\":\"localImage\",\"path\":\"/only.png\"}]");
    JSONArray result = ConversationContent.restoredInput(queued, "", new JSONArray());
    assertEquals(1, result.length());assertEquals("/only.png", result.getJSONObject(0).getString("path"));
  }
  @Test public void delayedStartReplyCannotEraseStreamedOutputOrReopenCompletedTurn() throws Exception {
    JSONObject live = new JSONObject("{\"id\":\"t\",\"status\":\"completed\",\"items\":[{\"id\":\"a\",\"type\":\"agentMessage\",\"text\":\"Finished\"}]}");
    JSONObject reply = new JSONObject("{\"id\":\"t\",\"status\":\"inProgress\",\"items\":[{\"id\":\"u\",\"type\":\"userMessage\",\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]}]}");
    assertSame(live, ConversationContent.startedTurn(live, reply));
    assertEquals("completed", live.getString("status"));
    assertEquals("userMessage", live.getJSONArray("items").getJSONObject(0).getString("type"));
    assertEquals("Finished", live.getJSONArray("items").getJSONObject(1).getString("text"));
    assertEquals(2, live.getJSONArray("items").length());
    ConversationContent.startedTurn(live, reply);
    assertEquals(2, live.getJSONArray("items").length());
  }
  @Test public void partialToolInputAndProgressRemainVisibleAlongsideOutput() throws Exception {
    JSONObject item = new JSONObject().put("type", "dynamicToolCall").put("command", "Read")
        .put("partialInput", "{\"file_path\":\"/tmp/").put("arguments", new JSONObject())
        .put("progressMessage", "Reading").put("aggregatedOutput", "first line").put("result", "first line");
    assertEquals("Read\n{\"file_path\":\"/tmp/\nReading\nfirst line", ConversationContent.item(item));
  }

  @Test public void indexedReasoningDeltasKeepSummaryPartsSeparate() throws Exception {
    JSONObject item = new JSONObject().put("type", "reasoning");
    ConversationContent.appendReasoning(item, new JSONObject().put("summaryIndex", 0).put("delta", "Plan"), true);
    ConversationContent.appendReasoning(item, new JSONObject().put("summaryIndex", 1).put("delta", "Read"), true);
    ConversationContent.appendReasoning(item, new JSONObject().put("summaryIndex", 1).put("delta", " files"), true);
    ConversationContent.appendReasoning(item, new JSONObject().put("contentIndex", 0).put("delta", "Inspect"), false);
    assertEquals("Plan\nRead files\n\nInspect", ConversationContent.item(item));
  }
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
    assertEquals(70, ConversationContent.sumTurnStat(thread, saved, "ms", "durationMs"));
    assertEquals(100, ConversationContent.sumTurnStat(thread, saved, "tokens", "billedTokens"));
    assertEquals(70, ConversationContent.sumTurnStat(thread, new JSONObject(), "ms", "durationMs"));
    assertEquals(0, ConversationContent.sumTurnStat(null, saved, "tokens", "billedTokens"));
  }
  @Test public void durationSurvivesReloadAndHandlesSecondsMillisAndUnknownTimes() throws Exception {
    assertEquals(2601, ConversationContent.durationMillis(new JSONObject("{\"durationMs\":2601}"), 50));
    assertEquals(2500, ConversationContent.durationMillis(new JSONObject("{\"startedAt\":1800000000,\"completedAt\":1800000002.5}"), 0));
    assertEquals(2500, ConversationContent.durationMillis(new JSONObject("{\"startedAt\":1800000000000,\"completedAt\":1800000002500}"), 0));
    assertEquals(50, ConversationContent.durationMillis(new JSONObject("{\"startedAt\":0,\"completedAt\":null}"), 50));
    assertEquals(0, ConversationContent.durationMillis(null, 0));
  }
  @Test public void nativeDynamicToolResultsAreVisibleWithoutDumpingMediaData() throws Exception {
    JSONObject tool = new JSONObject("{\"type\":\"dynamicToolCall\",\"contentItems\":[{\"type\":\"inputText\",\"text\":\"Created dist/app.apk\"},{\"type\":\"inputImage\",\"imageUrl\":\"data:image/png;base64,PRIVATE_BLOB\"}],\"error\":\"optional failure detail\"}");
    String output=ConversationContent.item(tool);
    assertTrue(output.contains("Created dist/app.apk"));assertTrue(output.contains("[图片结果]"));
    assertTrue(output.contains("optional failure detail"));assertFalse(output.contains("PRIVATE_BLOB"));
  }
}
