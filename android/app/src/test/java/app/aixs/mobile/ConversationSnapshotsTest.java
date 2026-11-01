package app.aixs.mobile;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConversationSnapshotsTest {
  private JSONObject json(String value) throws Exception { return new JSONObject(value); }
  @Test public void completionReloadKeepsTheLiveConversationAfterCacheEviction() throws Exception {
    JSONObject current = json("{\"id\":\"chat\",\"turns\":[{\"id\":\"done\",\"status\":\"completed\"}]}");
    assertSame(current, ConversationSnapshots.forLoad(current, null, "chat"));
    assertSame(current, ConversationSnapshots.forLoad(current, json("{\"id\":\"chat\",\"turns\":[]}"), "chat"));
    assertNull(ConversationSnapshots.forLoad(current, null, "other"));
    JSONObject cached = json("{\"id\":\"other\"}");
    assertSame(cached, ConversationSnapshots.forLoad(current, cached, "other"));
  }
  @Test public void queueHandoffKeepsAlreadyLoadedHistoryAndNewLocalTurns() throws Exception {
    JSONObject current = json("{\"id\":\"chat\",\"turns\":[{\"id\":\"old\",\"workflowId\":\"w\",\"status\":\"completed\",\"items\":[{\"id\":\"history\"},{\"id\":\"recent\"}]},{\"id\":\"started-live\",\"status\":\"inProgress\"}]}");
    JSONObject response = json("{\"id\":\"chat\",\"turns\":[{\"id\":\"old\",\"workflowId\":\"w\",\"status\":\"completed\",\"items\":[{\"id\":\"recent\"}]}],\"messageQueue\":[{\"status\":\"sent\"}]}");
    ConversationSnapshots.applyQueue(current, response);
    JSONArray turns = current.getJSONArray("turns");
    assertEquals(2, turns.length());assertEquals("started-live", turns.getJSONObject(1).getString("id"));
    assertEquals("history", turns.getJSONObject(0).getJSONArray("items").getJSONObject(0).getString("id"));
    assertEquals(1, current.getJSONArray("messageQueue").length());
  }
  @Test public void queueHandoffAppendsOnlyTheNewTurnAndPreservesLiveMemberOutput() throws Exception {
    JSONObject current = json("{\"id\":\"chat\",\"turns\":[{\"id\":\"old\",\"workflowId\":\"w\",\"status\":\"completed\",\"collaborationRevisions\":{\"a\":2},\"items\":[{\"id\":\"a:reply\",\"attemptId\":\"a\",\"type\":\"agentMessage\",\"text\":\"hello\"}]}]}");
    JSONObject response = json("{\"id\":\"chat\",\"turns\":[{\"id\":\"old\",\"workflowId\":\"w\",\"status\":\"completed\",\"collaborationRevisions\":{\"a\":1},\"items\":[{\"id\":\"a:reply\",\"attemptId\":\"a\",\"type\":\"agentMessage\",\"text\":\"hel\"}]},{\"id\":\"next\",\"status\":\"inProgress\",\"items\":[]}]}");
    ConversationSnapshots.applyQueue(current, response);
    JSONArray turns = current.getJSONArray("turns");
    assertEquals(2, turns.length());assertEquals("next", turns.getJSONObject(1).getString("id"));
    assertEquals("hello", turns.getJSONObject(0).getJSONArray("items").getJSONObject(0).getString("text"));
    assertEquals(2, turns.getJSONObject(0).getJSONObject("collaborationRevisions").getInt("a"));
  }
  @Test public void sameIdAndCountStillReconcileCompletionWithoutReopeningItLater() throws Exception {
    JSONObject current = json("{\"id\":\"chat\",\"turns\":[{\"id\":\"turn\",\"status\":\"inProgress\",\"items\":[]}]}");
    ConversationSnapshots.applyQueue(current, json("{\"id\":\"chat\",\"turns\":[{\"id\":\"turn\",\"status\":\"interrupted\",\"items\":[]}],\"executionControl\":{\"status\":\"interrupted\"}}"));
    assertEquals("interrupted", current.getJSONArray("turns").getJSONObject(0).getString("status"));
    ConversationSnapshots.applyQueue(current, json("{\"id\":\"chat\",\"turns\":[{\"id\":\"turn\",\"status\":\"inProgress\",\"items\":[]}]}"));
    assertEquals("interrupted", current.getJSONArray("turns").getJSONObject(0).getString("status"));
  }
  @Test public void unrelatedThreadCannotOverwriteQueueOrTurns() throws Exception {
    JSONObject current = json("{\"id\":\"chat\",\"turns\":[]}");
    String before = current.toString();
    ConversationSnapshots.applyQueue(current, json("{\"id\":\"other\",\"turns\":[{\"id\":\"wrong\"}],\"messageQueuePaused\":true}"));
    assertEquals(before, current.toString());
  }
  @Test public void aSnapshotMissingTheLatestItemCannotMoveItBeforeTheUserMessage() throws Exception {
    JSONObject current = json("{\"id\":\"chat\",\"turns\":[{\"id\":\"turn\",\"status\":\"inProgress\",\"items\":[{\"id\":\"user\"},{\"id\":\"reasoning\"},{\"id\":\"command\"}]}]}");
    JSONObject response = json("{\"id\":\"chat\",\"turns\":[{\"id\":\"turn\",\"status\":\"inProgress\",\"items\":[{\"id\":\"user\"},{\"id\":\"reasoning\"}]}]}");
    ConversationSnapshots.applyQueue(current, response);
    JSONArray items = current.getJSONArray("turns").getJSONObject(0).getJSONArray("items");
    assertEquals(3, items.length());
    assertEquals("user", items.getJSONObject(0).getString("id"));
    assertEquals("reasoning", items.getJSONObject(1).getString("id"));
    assertEquals("command", items.getJSONObject(2).getString("id"));
  }
}
