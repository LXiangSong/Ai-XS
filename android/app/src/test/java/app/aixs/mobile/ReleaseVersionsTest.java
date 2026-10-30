package app.aixs.mobile;

import static org.junit.Assert.*;
import java.net.URL;
import java.io.IOException;
import org.json.*;
import org.junit.Test;

public class ReleaseVersionsTest {
  private JSONObject release(String tag, boolean preview, boolean draft) throws Exception {
    return new JSONObject().put("tag_name", tag).put("prerelease", preview).put("draft", draft);
  }
  private JSONObject asset(String name) throws Exception {
    return new JSONObject().put("name", name).put("size", 35 * 1024 * 1024)
        .put("browser_download_url", ReleaseVersions.REPOSITORY + "/releases/download/v1.0.0/" + name);
  }
  @Test public void semanticOrderHandlesNumericVersionsPreviewsAndBuildMetadata() {
    assertTrue(ReleaseVersions.compare("v0.10.0", "0.9.9") > 0);
    assertTrue(ReleaseVersions.compare("1.0.0", "1.0.0-rc.9") > 0);
    assertTrue(ReleaseVersions.compare("1.0.0-rc.10", "1.0.0-rc.2") > 0);
    assertTrue(ReleaseVersions.compare("1.0.0-alpha", "1.0.0-beta") < 0);
    assertTrue(ReleaseVersions.compare("1.0.0-1", "1.0.0-alpha") < 0);
    assertEquals(0, ReleaseVersions.compare("1.0.0+build.8", "v1.0.0+build.9"));
    assertTrue(ReleaseVersions.prerelease("v1.0.0-alpha"));
  }
  @Test public void highestVersionWinsRatherThanGithubPublicationOrder() throws Exception {
    JSONArray entries = new JSONArray().put(release("v0.9.0", false, false)).put(release("v0.8.9", false, false))
        .put(release("v1.0.0-beta.2", true, false)).put(release("v8.0.0", false, true)).put(release("nightly", false, false));
    assertEquals("v0.9.0", ReleaseVersions.latest(entries, false).getString("tag_name"));
    assertEquals("v1.0.0-beta.2", ReleaseVersions.latest(entries, true).getString("tag_name"));
    assertNull(ReleaseVersions.latest(new JSONArray(), true));
    assertNull(ReleaseVersions.latest(new JSONArray().put(release("1.0.0-alpha", false, false)), false));
  }
  @Test public void selectsArm64ApkAndIgnoresWrongRepositoryOrArchitecture() throws Exception {
    JSONObject document = release("1.0.0", false, false).put("assets", new JSONArray()
        .put(asset("Ai-XS-x86_64.apk")).put(asset("Ai-XS-universal.apk")).put(asset("Ai-XS-arm64-v8a.apk"))
        .put(asset("Ai-XS-debug-arm64.apk")).put(asset("Ai-XS.apk").put("browser_download_url", "https://example.com/update.apk")));
    assertEquals("Ai-XS-arm64-v8a.apk", ReleaseVersions.apk(document).getString("name"));
    assertNull(ReleaseVersions.apk(release("1.0.0", false, false)));
    document.put("assets", new JSONArray().put(asset("Ai-XS.apk").put("size", ReleaseVersions.MAX_APK + 1)));
    assertNull(ReleaseVersions.apk(document));
  }
  @Test public void agentsUseTheirActualOfficialDistributionPackages() {
    assertEquals("@openai/codex", ReleaseVersions.npmPackage("codex"));
    assertEquals("@anthropic-ai/claude-code-linux-arm64", ReleaseVersions.npmPackage("claude"));
    assertEquals("@deepseek-ai/dsh", ReleaseVersions.npmPackage("deepseek"));
  }
  @Test public void updateRedirectsCannotDowngradeToHttpOrOtherHosts() throws Exception {
    AppUpdater.checkHost(new URL("https://github.com/LXiangSong/Ai-XS/releases/download/v1/demo.apk"));
    AppUpdater.checkHost(new URL("https://release-assets.githubusercontent.com/github-production-release-asset/demo"));
    for (String address : new String[] {"http://github.com/update.apk", "https://github.com.attacker.test/demo", "https://github.com:8443/demo", "https://name:password@github.com/demo"}) {
      try { AppUpdater.checkHost(new URL(address)); fail(address); } catch (IOException expected) {}
    }
  }
  @Test public void publishedDigestIsEnforcedWhenAvailable() throws Exception {
    String hash = "a".repeat(64);
    AppUpdater.verifyDigest("sha256:" + hash, hash);
    AppUpdater.verifyDigest("", hash);
    for (String value : new String[] {"sha256:" + "b".repeat(64), "sha256:broken", "sha512:" + hash}) {
      try { AppUpdater.verifyDigest(value, hash); fail(value); } catch (IOException expected) {}
    }
  }
}
