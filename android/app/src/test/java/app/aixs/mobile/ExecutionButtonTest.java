package app.aixs.mobile;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;
import static app.aixs.mobile.ExecutionButton.Action.*;

public class ExecutionButtonTest {
  private JSONObject stopped(String entries) throws Exception {
    return new JSONObject("{\"id\":\"chat\",\"executionControl\":{\"status\":\"interrupted\"},\"messageQueue\":" + entries + "}");
  }
  @Test public void failedOrUnknownHeadCannotPretendTheNextEntryWillAutoRun() throws Exception {
    for (String status : new String[]{"failed", "needs_reconciliation", "including"}) {
      JSONObject thread = stopped("[{\"status\":\"" + status + "\"},{\"status\":\"queued\"}]");
      assertFalse(ExecutionButton.autoQueuePending(thread));
      assertEquals(RESUME, ExecutionButton.action(false, false, "interrupted"));
    }
  }
  @Test public void fifoSkipsReceiptsButNotAContinuationOrExplicitPause() throws Exception {
    JSONObject thread = stopped("[{\"status\":\"sent\"},null,{\"status\":\"deleted\"},{\"status\":\"queued\"}]");
    assertTrue(ExecutionButton.autoQueuePending(thread));
    thread.put("messageQueuePaused", true);
    assertFalse(ExecutionButton.autoQueuePending(thread));
    thread.put("messageQueuePaused", false);
    thread.getJSONArray("messageQueue").getJSONObject(3).put("executionResume", true);
    assertFalse(ExecutionButton.autoQueuePending(thread));
  }
  @Test public void runningWithContentSendsAndClearingItShowsStop() {
    assertEquals(SEND,ExecutionButton.action(true,true,""));
    assertEquals(STOP,ExecutionButton.action(false,true,""));
  }
  @Test public void emptyInterruptedComposerContinuesAndNewContentSends() {
    assertEquals(RESUME,ExecutionButton.action(false,false,"interrupted"));
    assertEquals(SEND,ExecutionButton.action(true,false,"interrupted"));
  }
  @Test public void noDoubleStopOrContinueWhileHandoffIsInProgress() {
    assertEquals(WAIT,ExecutionButton.action(false,true,"stopping"));
    assertEquals(WAIT,ExecutionButton.action(false,false,"resuming"));
    assertEquals(SEND,ExecutionButton.action(true,false,"resuming"));
  }
  @Test public void completedTaskIsNotResumedAndIdleEmptyComposerDoesNothing() {
    assertEquals(IDLE,ExecutionButton.action(false,false,"running"));
    assertEquals(IDLE,ExecutionButton.action(false,false,""));
    assertEquals(IDLE,ExecutionButton.action(false,false,"superseded"));
    assertEquals(STOP,ExecutionButton.action(false,true,"running"));
  }
}
