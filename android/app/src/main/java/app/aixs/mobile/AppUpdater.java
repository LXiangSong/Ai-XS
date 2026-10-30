package app.aixs.mobile;

import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Download into private storage, verify before handing the APK to Android. */
final class AppUpdater {
  static File folder(Context context) { return new File(context.getFilesDir(), "app-updates"); }
  static File apk(Context context) { return new File(folder(context), "Ai-XS-update.apk"); }
  static File stateFile(Context context) { return new File(folder(context), "state.json"); }
  static JSONObject state(Context context) {
    try { return RuntimeManager.json(stateFile(context)); } catch (Exception ignored) { return new JSONObject(); }
  }
  static void state(Context context, String status, String message) throws Exception {
    RuntimeManager.write(stateFile(context), new JSONObject().put("status", status).put("message", Diagnostics.redact(context, message))
        .put("fromVersion", version(context)).put("updatedAt", System.currentTimeMillis()).toString());
  }
  static String version(Context context) {
    try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
    catch (Exception ignored) { return "0.0.0"; }
  }
  static void checkHost(URL url) throws IOException {
    String host = url.getHost().toLowerCase(Locale.ROOT);
    if (!url.getProtocol().equals("https") || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)
        || !(host.equals("github.com") || host.endsWith(".githubusercontent.com")))
      throw new IOException("更新下载地址必须来自 GitHub Releases");
  }
  static void verifyDigest(String expected, String actual) throws IOException {
    if (expected != null && !expected.isEmpty() && !expected.equals("null")) {
      if (!expected.matches("sha256:[a-fA-F0-9]{64}") || !expected.substring(7).equalsIgnoreCase(actual))
        throw new IOException("更新包 SHA-256 校验失败，请重新下载");
    }
  }
  static void download(Context context, JSONObject release) throws Exception {
    File partial = new File(folder(context), "update.part");
    folder(context).mkdirs();
    apk(context).delete();
    try {
      JSONObject asset = release.getJSONObject("asset");
      String address = asset.getString("browser_download_url");
      if (!address.startsWith(ReleaseVersions.REPOSITORY + "/releases/download/")) throw new IOException("更新源与项目仓库不一致");
      long expectedSize = asset.getLong("size");
      if (expectedSize <= 0 || expectedSize > ReleaseVersions.MAX_APK) throw new IOException("更新包大小无效");
      if (new android.os.StatFs(folder(context).getAbsolutePath()).getAvailableBytes() < expectedSize + 16 * 1024 * 1024)
        throw new IOException("空间不足，请清理存储后重试");
      URL url = new URL(address);
      boolean complete = false;
      for (int redirects = 0; redirects < 6; redirects++) {
        checkHost(url);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "Ai-XS-Android-Updater");
        try {
          int code = connection.getResponseCode();
          if (code >= 300 && code < 400) {
            String location = connection.getHeaderField("Location");
            if (location == null) throw new IOException("更新下载重定向缺少地址");
            url = new URL(url, location); continue;
          }
          if (code != 200) throw new IOException("更新下载失败，HTTP " + code);
          MessageDigest digest = MessageDigest.getInstance("SHA-256");
          long size = 0, lastUpdate = 0;
          try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(partial)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = in.read(buffer)) != -1) {
              size += count;
              if (size > expectedSize || size > ReleaseVersions.MAX_APK) throw new IOException("更新包大小与发布信息不符");
              out.write(buffer, 0, count); digest.update(buffer, 0, count);
              if (System.currentTimeMillis() - lastUpdate >= 700) {
                state(context, "downloading", "正在下载 " + release.getString("version") + " · " + (size * 100 / expectedSize) + "%");
                lastUpdate = System.currentTimeMillis();
              }
            }
          }
          if (size != expectedSize) throw new IOException("更新包下载不完整，请重试");
          StringBuilder hash = new StringBuilder();
          for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
          verifyDigest(asset.optString("digest"), hash.toString());
          PackageInfo candidate = verifyApk(context, partial);
          if (ReleaseVersions.compare(candidate.versionName, release.getString("version")) != 0)
            throw new IOException("APK 版本与 GitHub 发布标签不一致");
          if (!partial.renameTo(apk(context))) throw new IOException("无法保存更新安装包");
          state(context, "ready", "更新包已校验，点击安装 " + release.getString("version"));
          complete = true; break;
        } finally { connection.disconnect(); }
      }
      if (!complete) throw new IOException("更新下载重定向次数过多");
    } finally { partial.delete(); }
  }
  @SuppressWarnings("deprecation")
  static PackageInfo verifyApk(Context context, File file) throws Exception {
    PackageManager manager = context.getPackageManager();
    int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
    PackageInfo candidate = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
    if (candidate == null || !context.getPackageName().equals(candidate.packageName)) throw new IOException("安装包不是 Ai-XS");
    long currentCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(installed);
    long nextCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(candidate);
    if (nextCode <= currentCode) throw new IOException("安装包版本不高于当前版本，无需更新");
    if (candidate.applicationInfo != null && candidate.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)
      throw new IOException("该更新需要更高版本的 Android");
    Signature[] current = Build.VERSION.SDK_INT >= 28 && installed.signingInfo != null
        ? installed.signingInfo.getApkContentsSigners() : installed.signatures;
    Signature[] next = Build.VERSION.SDK_INT >= 28 && candidate.signingInfo != null
        ? candidate.signingInfo.getApkContentsSigners() : candidate.signatures;
    if (current == null || next == null || current.length == 0 || !new HashSet<>(Arrays.asList(current)).equals(new HashSet<>(Arrays.asList(next))))
      throw new IOException("更新包签名与已安装版本不同，无法覆盖更新");
    return candidate;
  }
}
