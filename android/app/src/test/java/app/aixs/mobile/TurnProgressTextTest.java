package app.aixs.mobile;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public class TurnProgressTextTest {
  @Test public void waitingDoesNotClaimThinkingOrToolExecution() throws Exception {
    assertEquals("等待接口", TurnProgressText.title(new JSONObject().put("phase", "waiting")));
    assertEquals("等待事件", TurnProgressText.title(null));
    assertEquals("思考中", TurnProgressText.title(new JSONObject().put("phase", "thinking")));
  }
  @Test public void nativeRetryMetadataAndToolNameRemainVisible() throws Exception {
    JSONObject retry = new JSONObject().put("phase", "retrying").put("attempt", 2)
        .put("maxRetries", 10).put("httpStatus", 529).put("retryDelayMs", 1500);
    assertEquals("接口重试 2/10", TurnProgressText.title(retry));
    assertEquals("HTTP 529 · 重试间隔 2 秒", TurnProgressText.retryDetail(retry));
    assertEquals("Bash", TurnProgressText.title(new JSONObject().put("phase", "tool").put("detail", "Bash")));
  }
}
