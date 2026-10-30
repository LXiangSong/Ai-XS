package app.aixs.mobile;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class AgentViewCacheTest {
  @Test public void recentViewsRetainLiveUpdatesAndEvictOldest() throws Exception {
    AgentViewCache cache = new AgentViewCache();
    JSONObject a = new JSONObject().put("id", "a");
    JSONObject tokens = new JSONObject().put("tokens", 20);
    cache.remember(a, tokens);
    cache.remember(new JSONObject().put("id", "b"), tokens);
    cache.remember(new JSONObject().put("id", "c"), tokens);
    assertSame(a, cache.thread("a"));
    a.put("name", "stream updated");
    assertEquals("stream updated", cache.thread("a").getString("name"));
    cache.remember(new JSONObject().put("id", "d"), null);
    assertNull(cache.thread("b"));
    assertNull(cache.usage("b"));
    assertSame(tokens, cache.usage("a"));
    cache.models("codex", new JSONObject().put("model", "one"));
    cache.models("deepseek", new JSONObject().put("model", "two"));
    assertEquals("one", cache.models("codex").getString("model"));
    assertEquals("two", cache.models("deepseek").getString("model"));
    assertNull(cache.models("claude"));
    cache.forget("a");
    assertNull(cache.thread("a"));
    assertNull(cache.usage("a"));
  }
}
