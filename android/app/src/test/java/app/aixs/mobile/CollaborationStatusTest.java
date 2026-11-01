package app.aixs.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CollaborationStatusTest {
  @Test public void statesUseTheContractVocabulary() {
    assertEquals("执行完成", CollaborationStatus.execution("succeeded"));
    assertEquals("待人工核验", CollaborationStatus.verification("manual_required"));
    assertEquals("待验收", CollaborationStatus.delivery("awaiting_acceptance"));
    assertEquals("审核", CollaborationStatus.kind("review"));
  }

  @Test public void budgetLineNeverClaimsExactSpendAndMarksPartialTokens() throws Exception {
    JSONObject usage = new JSONObject().put("attempts", 3)
        .put("elapsedSeconds", 125).put("reportedTokens", 1200).put("tokensPartial", true)
        .put("limits", new JSONObject().put("maxAttempts", 12).put("maxReportedTokens", 50000));
    String line = CollaborationStatus.budgetLine(usage);
    assertTrue(line.contains("执行 3 次 / 上限 12"));
    assertTrue(line.contains("2 分 5 秒"));
    assertTrue(line.contains("可能不完整"));
    assertFalse(line.contains("金额"));
  }

  @Test public void verificationSummaryCountsEvidence() throws Exception {
    JSONObject verification = new JSONObject().put("checks", new JSONArray()
        .put(new JSONObject().put("passed", true)).put(new JSONObject().put("passed", false)))
        .put("review", new JSONObject().put("approved", false));
    String text = CollaborationStatus.verificationSummary(verification);
    assertTrue(text.contains("1 通过"));
    assertTrue(text.contains("1 失败"));
    assertTrue(text.contains("审核未通过"));
  }
}
