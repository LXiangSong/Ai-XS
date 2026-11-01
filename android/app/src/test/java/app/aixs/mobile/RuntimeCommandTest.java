package app.aixs.mobile;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class RuntimeCommandTest {
  @Test public void blockedPythonHasNamedTimeoutAndLastOutput() throws Exception {
    long start=System.nanoTime();
    try {
      RuntimeCommand.run(new ProcessBuilder("sh","-c","echo 'bytecode begin'; exec sleep 20"),"Python 字节码编译",1,s->{},s->{});
      fail();
    }catch(RuntimeCommand.Failure error){
      assertTrue(error.timeout);assertTrue(error.getMessage().contains("Python 字节码编译"));assertTrue(error.output.contains("bytecode begin"));
    }
    assertTrue((System.nanoTime()-start)/1_000_000 < 5000);
  }
  @Test public void failedExecutableRetainsExitCodeAndOutput() throws Exception {
    try{RuntimeCommand.run(new ProcessBuilder("sh","-c","echo 'missing library' >&2; exit 17"),"Git",3,s->{},s->{});fail();}
    catch(RuntimeCommand.Failure e){assertFalse(e.timeout);assertEquals(17,e.exit);assertTrue(e.output.contains("missing library"));}
  }
  @Test public void networkAndRuntimeFailuresAreDistinguished() {
    assertTrue(RuntimeChecks.reason("network-npm",new Exception("curl: (6) Could not resolve host")).contains("DNS"));
    assertTrue(RuntimeChecks.reason("tls",new Exception("certificate verify failed")).contains("TLS"));
    assertTrue(RuntimeChecks.reason("python-start",new RuntimeCommand.Failure("Python",true,-1,"")).contains("Python"));
    assertTrue(RuntimeChecks.reason("git",new Exception("error while loading shared libraries")).contains("动态库"));
  }
}
