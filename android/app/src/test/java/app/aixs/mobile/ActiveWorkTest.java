package app.aixs.mobile;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActiveWorkTest {
  private JSONObject event(String method,String thread,String turn) throws Exception {
    return new JSONObject().put("method",method).put("params",new JSONObject().put("threadId",thread).put("turn",new JSONObject().put("id",turn)));
  }
  @Test public void parallelTurnsReleaseOnlyAfterLastCompletionAndIgnoreDuplicateStarts() throws Exception {
    ActiveWork work=new ActiveWork();
    work.observe(event("turn/started","a","1"));work.observe(event("turn/started","a","1"));work.observe(event("turn/started","b","1"));
    assertTrue(work.active());work.observe(event("turn/completed","a","1"));assertTrue(work.active());
    work.observe(event("turn/completed","b","1"));assertFalse(work.active());
  }
  @Test public void stoppedTransportClearsOutstandingWork() throws Exception {
    ActiveWork work=new ActiveWork();work.observe(event("turn/started","a","1"));
    work.observe(new JSONObject().put("type","native/stopped"));assertFalse(work.active());
  }
  @Test public void reconcileAbruptWorkerExitPreservesStartsAfterSnapshot() throws Exception {
    ActiveWork work=new ActiveWork();
    work.observe(event("turn/started","a","1").put("attemptId","worker"));
    long checkpoint=work.checkpoint();
    work.observe(event("turn/started","b","2"));
    work.reconcile(new JSONArray(),new JSONArray(),checkpoint);assertTrue(work.active());
    work.observe(event("turn/completed","b","2"));assertFalse(work.active());
  }
}
