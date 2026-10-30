package app.aixs.mobile;

import java.math.BigInteger;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Release selection shared by app and Agent updates; independent of Android UI. */
final class ReleaseVersions {
  static final String REPOSITORY = "https://github.com/LXiangSong/Ai-XS";
  static final String RELEASES_API = "https://api.github.com/repos/LXiangSong/Ai-XS/releases?per_page=100";
  static final long MAX_APK = 512L * 1024 * 1024;
  private static final Pattern VERSION = Pattern.compile("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");

  static int compare(String left, String right) {
    Matcher a = VERSION.matcher(left.trim()), b = VERSION.matcher(right.trim());
    if (!a.matches() || !b.matches()) throw new IllegalArgumentException("无法识别版本号：" + left + " / " + right);
    for (int i = 1; i <= 3; i++) {
      int order = new BigInteger(a.group(i)).compareTo(new BigInteger(b.group(i)));
      if (order != 0) return order;
    }
    String ap = a.group(4), bp = b.group(4);
    if (ap == null || bp == null) return ap == bp ? 0 : ap == null ? 1 : -1;
    String[] av = ap.split("\\."), bv = bp.split("\\.");
    for (int i = 0; i < Math.min(av.length, bv.length); i++) {
      boolean an = av[i].matches("\\d+"), bn = bv[i].matches("\\d+");
      int order = an && bn ? new BigInteger(av[i]).compareTo(new BigInteger(bv[i]))
          : an != bn ? (an ? -1 : 1) : av[i].compareTo(bv[i]);
      if (order != 0) return order;
    }
    return Integer.compare(av.length, bv.length);
  }

  static boolean prerelease(String value) {
    Matcher match = VERSION.matcher(value.trim());
    return match.matches() && match.group(4) != null;
  }

  static JSONObject latest(JSONArray releases, boolean includePreview) throws JSONException {
    JSONObject selected = null;
    for (int i = 0; i < releases.length(); i++) {
      JSONObject release = releases.optJSONObject(i);
      if (release == null || release.optBoolean("draft")) continue;
      String version = release.optString("tag_name");
      if (!includePreview && (release.optBoolean("prerelease") || prerelease(version))) continue;
      try {
        compare(version, version);
        if (selected == null || compare(version, selected.getString("tag_name")) > 0) selected = release;
      } catch (IllegalArgumentException ignored) {}
    }
    return selected;
  }

  static JSONObject apk(JSONObject release) {
    JSONArray assets = release.optJSONArray("assets");
    JSONObject best = null;
    int bestScore = -1;
    if (assets == null) return null;
    for (int i = 0; i < assets.length(); i++) {
      JSONObject asset = assets.optJSONObject(i);
      if (asset == null) continue;
      String name = asset.optString("name").toLowerCase(Locale.ROOT);
      long size = asset.optLong("size");
      if (!name.endsWith(".apk") || name.contains("x86") || name.contains("x64") || name.contains("amd64")
          || name.contains("armeabi") || name.contains("armv7") || name.contains("arm32") || name.contains("debug")
          || !name.replaceAll("[_-]", "").startsWith("aixs") || size <= 0 || size > MAX_APK
          || !"uploaded".equals(asset.optString("state", "uploaded"))) continue;
      if (!asset.optString("browser_download_url").startsWith(REPOSITORY + "/releases/download/")) continue;
      int score = name.contains("arm64") ? 3 : name.contains("universal") ? 2 : 1;
      if (score > bestScore) { best = asset; bestScore = score; }
    }
    return best;
  }

  static String npmPackage(String agent) {
    switch (agent) {
      case "codex": return "@openai/codex";
      case "claude": return "@anthropic-ai/claude-code-linux-arm64";
      case "deepseek": return "@deepseek-ai/dsh";
      default: throw new IllegalArgumentException("未知 Agent");
    }
  }
}
