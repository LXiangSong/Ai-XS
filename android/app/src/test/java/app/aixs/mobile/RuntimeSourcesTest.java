package app.aixs.mobile;

import static org.junit.Assert.*;
import org.junit.Test;

public class RuntimeSourcesTest {
  @Test public void arm64Deb822IncludesSecurityAndKeepsUbuntuSignature() {
    String input="Types: deb\nURIs: http://ports.ubuntu.com/ubuntu-ports/\nSuites: noble noble-updates noble-backports\nComponents: main universe\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n\n"
        +"Types: deb\nURIs: http://ports.ubuntu.com/ubuntu-ports\nSuites: noble-security\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n";
    String output=RuntimeSources.rewriteApt(input,RuntimeSources.APT_MIRRORS[0]);
    assertFalse(output.contains("ports.ubuntu.com"));
    assertTrue(output.contains("Suites: noble-security"));
    assertTrue(output.contains("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg"));
    assertEquals(2,output.split("URIs: "+java.util.regex.Pattern.quote(RuntimeSources.APT_MIRRORS[0]),-1).length-1);
    assertEquals(output,RuntimeSources.rewriteApt(output,RuntimeSources.APT_MIRRORS[0]));
    assertEquals("http://ports.ubuntu.com/ubuntu-ports/",RuntimeSources.activeUbuntuUri(input));
    assertEquals(RuntimeSources.APT_MIRRORS[0],RuntimeSources.activeUbuntuUri(output));
    String fallback=RuntimeSources.rewriteApt(output,RuntimeSources.APT_MIRRORS[1]);
    assertFalse(fallback.contains("ustc.edu.cn"));assertTrue(fallback.contains("tuna.tsinghua.edu.cn/ubuntu-ports/"));
  }
  @Test public void legacySourceOptionsCommentsAndThirdPartyRepositoriesSurvive() {
    String custom="deb [signed-by=/etc/vendor.gpg] https://vendor.example/ubuntu noble main\n";
    String input="# http://ports.ubuntu.com/ubuntu-ports/ documentation\n"
        +"deb [arch=arm64 signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] http://ports.ubuntu.com/ubuntu-ports noble main\n"+custom;
    String output=RuntimeSources.rewriteApt(input,RuntimeSources.APT_MIRRORS[0]);
    assertTrue(output.startsWith("# http://ports.ubuntu.com/ubuntu-ports/ documentation"));assertTrue(output.endsWith(custom));
    assertTrue(output.contains("[arch=arm64 signed-by=/usr/share/keyrings/ubuntu-archive-keyring.gpg] "+RuntimeSources.APT_MIRRORS[0]));
    assertEquals(custom,RuntimeSources.rewriteApt(custom,RuntimeSources.APT_MIRRORS[1]));
    assertEquals("",RuntimeSources.activeUbuntuUri(custom));
  }
  @Test public void nodeUrlsCannotInjectShellCommands() {
    assertTrue(RuntimeSources.nodeUrl(0,"v24.22.1").endsWith("/v24.22.1/node-v24.22.1-linux-arm64.tar.xz"));
    for(String version:new String[]{"v24.22.1;id","../escape","v24..1"}) {
      try{RuntimeSources.nodeUrl(0,version);fail();}catch(IllegalArgumentException expected){}
    }
  }
}
