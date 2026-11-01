package app.aixs.mobile;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Public release metadata only; never forwards API credentials. */
final class UpdateSources {
  static String get(String address) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(20000);
    connection.setReadTimeout(25000);
    connection.setRequestProperty("User-Agent", "Ai-XS-Android-Updater");
    connection.setRequestProperty("Accept", address.contains("api.github.com") ? "application/vnd.github+json" : "application/json");
    try {
      int code = connection.getResponseCode();
      if (code == 403 || code == 429) throw new IOException("更新源访问受限，请稍后重试（HTTP " + code + "）");
      if (code == 404) throw new IOException("未找到更新源或发布版本（HTTP 404）");
      if (code != 200) throw new IOException("检查更新失败，HTTP " + code);
      try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[16384]; int count;
        while ((count = in.read(buffer)) != -1) {
          if (out.size() + count > 8 * 1024 * 1024) throw new IOException("更新信息过大");
          out.write(buffer, 0, count);
        }
        return out.toString(StandardCharsets.UTF_8.name());
      }
    } finally { connection.disconnect(); }
  }

  static JSONObject agent(String id) throws Exception {
    String packageName = ReleaseVersions.npmPackage(id);
    JSONObject metadata = new JSONObject(get(RuntimeSources.NPM + URLEncoder.encode(packageName, "UTF-8") + "/latest"));
    String version = metadata.getString("version");
    ReleaseVersions.compare(version, version);
    return new JSONObject().put("agent", id).put("version", version).put("package", packageName);
  }

  static JSONObject app(String current, boolean previews) throws Exception {
    JSONObject release = ReleaseVersions.latest(new JSONArray(get(ReleaseVersions.RELEASES_API)), previews);
    JSONObject result = new JSONObject().put("checkedAt", System.currentTimeMillis()).put("current", current);
    if (release == null) return result.put("status", "empty").put("message", previews ? "GitHub 暂无可用发布版本" : "GitHub 暂无正式发布版本，可开启测试版检查");
    String tag = release.getString("tag_name");
    JSONObject asset = ReleaseVersions.apk(release);
    result.put("version", tag).put("notes", release.optString("body")).put("publishedAt", release.optString("published_at"))
        .put("releaseUrl", ReleaseVersions.REPOSITORY + "/releases/tag/" + URLEncoder.encode(tag, "UTF-8"));
    if (ReleaseVersions.compare(tag, current) <= 0)
      return result.put("status", "current").put("message", "当前已是最新版本（发布版本 " + tag + "）");
    if (asset == null) return result.put("status", "no-apk").put("message", "发现 " + tag + "，但该 Release 尚未上传适用于 Ai-XS 的 ARM64 APK");
    return result.put("status", "available").put("message", "发现新版本 " + tag).put("asset", asset);
  }
}
