package app.aixs.mobile;

import static org.junit.Assert.*;
import org.junit.Test;

public class DiagnosticsTest {
  @Test public void hidesCredentialsAndUrlQueriesBeforeExport() {
    String safe = Diagnostics.redact("Bearer secret-auth api_key=secret-api \"token\":\"secret-token\" "
        + "password: secret-password https://example.test/error?key=private-value&trace=1");
    assertFalse(safe.contains("secret-"));
    assertFalse(safe.contains("private-value"));
    assertTrue(safe.contains("https://example.test/error?"));
    assertTrue(safe.contains("[已隐藏]"));
    assertEquals("", Diagnostics.redact((String) null));
  }
}
