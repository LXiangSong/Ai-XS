package app.aixs.mobile;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class OperationStateTest {
  @Test public void restoredResultStaysOnItsOriginUntilExplicitlyDismissed() throws Exception {
    JSONObject running = OperationState.start("op-result", "check", null, "environment", "检查", 100);
    assertTrue(OperationState.shouldDisplay(running, "agents", "op-result"));
    for (String status : new String[] {"completed", "failed", "interrupted"}) {
      JSONObject restored = new JSONObject(OperationState.finish(running, status, "最终结果", 200).toString());
      assertTrue(OperationState.shouldDisplay(restored, "environment", ""));
      assertFalse(OperationState.shouldDisplay(restored, "agents", ""));
      assertFalse(OperationState.shouldDisplay(restored, "environment", "op-result"));
    }
    assertFalse(OperationState.shouldDisplay(new JSONObject(), "environment", ""));
  }
  @Test public void failureRetainsOriginAndCorrelationAcrossReload() throws Exception {
    JSONObject running = OperationState.start("op12", "agent-select", "claude", "agents", "切换版本", 100);
    JSONObject stage = OperationState.step(running, "正在恢复服务", 140);
    JSONObject failed = OperationState.finish(stage, "failed", "恢复失败", 200);
    JSONObject reloaded = new JSONObject(failed.toString());
    assertEquals("agents", reloaded.getString("origin"));
    assertEquals("op12", reloaded.getString("id"));
    assertEquals("claude", reloaded.getString("agent"));
    assertEquals(100, reloaded.getLong("startedAt"));
    assertEquals(200, reloaded.getLong("finishedAt"));
    assertEquals("running", running.getString("status"));
    assertEquals("恢复失败", OperationState.step(reloaded, "迟到输出", 250).getString("message"));
    assertEquals(200, OperationState.step(reloaded, "迟到输出", 250).getLong("updatedAt"));
  }
  @Test public void limitsStageHistoryAndPreservesFinalStage() throws Exception {
    JSONObject state = OperationState.start("a", "initialize", null, "environment", "初始化", 10);
    for (int i = 0; i < 30; i++) state = OperationState.step(state, "阶段 " + i, 20 + i);
    assertEquals(8, state.getJSONArray("steps").length());
    assertEquals("阶段 22", state.getJSONArray("steps").getJSONObject(0).getString("message"));
    JSONObject done = OperationState.finish(state, "completed", "已完成", 80);
    assertEquals("阶段 29", done.getJSONArray("steps").getJSONObject(7).getString("message"));
    assertEquals("completed", OperationState.step(done, "输出", 90).getString("status"));
  }
  @Test(expected = IllegalArgumentException.class) public void refusesUnknownResult() throws Exception {
    OperationState.finish(new JSONObject(), "success-ish", "", 10);
  }
}
