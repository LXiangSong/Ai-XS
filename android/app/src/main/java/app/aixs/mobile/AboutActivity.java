package app.aixs.mobile;

import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.*;
import org.json.*;

/** App information and GitHub updates work even before Linux initialization. */
public final class AboutActivity extends androidx.appcompat.app.AppCompatActivity {
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private Button check, download, install;
  private TextView result, notes, progress;
  private JSONObject release;
  private boolean checking, autoInstall, installing;
  private com.google.android.material.materialswitch.MaterialSwitch previews;
  private final Runnable refresh = new Runnable() {
    public void run() { refreshDownload(); handler.postDelayed(this, 800); }
  };
  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    autoInstall = saved != null && saved.getBoolean("autoInstall");
    LinearLayout root = NativeUi.page(this, "关于 Ai-XS", this::finish);
    LinearLayout body = NativeUi.scroll(this, root);
    NativeUi.heading(this, body, "Ai-XS", "手机上的原生 AI 编程工作台");
    NativeUi.statusCard(this, body, "当前版本 " + AppUpdater.version(this) + "\nCodex · Claude Code · DeepSeek");
    body.addView(NativeUi.body(this, "三个 Agent 共用运行环境，在同一项目和会话中协作。支持官方及第三方 API、Skills、插件和 MCP 扩展。"));
    NativeUi.section(this, body, "项目信息");
    NativeUi.tile(this, body, R.drawable.ic_code, "GitHub 项目", "LXiangSong / Ai-XS", () -> open(ReleaseVersions.REPOSITORY));
    NativeUi.tile(this, body, R.drawable.ic_log, "发布记录", "查看历史版本与更新说明", () -> open(ReleaseVersions.REPOSITORY + "/releases"));
    NativeUi.tile(this, body, R.drawable.ic_chat, "反馈问题", "在 GitHub Issues 提交问题与建议", () -> open(ReleaseVersions.REPOSITORY + "/issues"));
    NativeUi.section(this, body, "应用更新");
    previews = new com.google.android.material.materialswitch.MaterialSwitch(this);
    previews.setText("包含测试版更新"); previews.setTextColor(NativeUi.TEXT);
    previews.setChecked(getPreferences(0).getBoolean("previews", ReleaseVersions.prerelease(AppUpdater.version(this))));
    previews.setOnCheckedChangeListener((button, checked) -> {
      getPreferences(0).edit().putBoolean("previews", checked).apply();
      new File(AppUpdater.folder(this), "check.json").delete();
      release = null; download.setVisibility(android.view.View.GONE);
      result.setText("更新渠道已切换，请重新检查"); notes.setText("");
    });
    body.addView(previews);
    check = NativeUi.primaryButton(this, "检查 GitHub 更新", () -> check(previews.isChecked()));
    body.addView(check);
    result = NativeUi.statusCard(this, body, "点击检查更新，获取 GitHub Releases 中的最新版本。");
    download = NativeUi.outlinedButton(this, "下载并安装更新", this::download);
    download.setVisibility(android.view.View.GONE); body.addView(download);
    progress = NativeUi.body(this, ""); body.addView(progress);
    install = NativeUi.primaryButton(this, "安装已下载更新", this::install);
    install.setVisibility(android.view.View.GONE); body.addView(install);
    notes = NativeUi.body(this, ""); notes.setTextIsSelectable(true); body.addView(notes);
    if (saved != null) try { show(new JSONObject(saved.getString("release", "{}"))); } catch (Exception ignored) {}
    if (saved == null) try { show(RuntimeManager.json(new File(AppUpdater.folder(this), "check.json"))); } catch (Exception ignored) {}
    JSONObject state = AppUpdater.state(this);
    if (state.optString("status").equals("ready") && !state.optString("fromVersion").equals(AppUpdater.version(this))) {
      AppUpdater.apk(this).delete();
      try { AppUpdater.state(this, "completed", "已完成应用更新"); } catch (Exception ignored) {}
    }
    if (state.optString("status").equals("downloading") && !AppUpdateService.active()) {
      new File(AppUpdater.folder(this), "update.part").delete();
      try { AppUpdater.state(this, "failed", "上次下载被中断，请重新下载更新"); } catch (Exception ignored) {}
    }
  }
  private void open(String address) {
    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address))); }
    catch (Exception error) { NativeUi.error(this, "未找到可打开链接的浏览器"); }
  }
  private void check(boolean previews) {
    if (checking) return;
    checking = true; check.setEnabled(false); check.setText("正在检查…");
    this.previews.setEnabled(false);
    download.setVisibility(android.view.View.GONE); release = null;
    result.setText("正在连接 GitHub Releases…"); notes.setText("");
    worker.execute(() -> {
      try {
        JSONObject found = UpdateSources.app(AppUpdater.version(this), previews);
        RuntimeManager.write(new File(AppUpdater.folder(this), "check.json"), found.toString());
        runOnUiThread(() -> { if (!isDestroyed()) show(found); });
      } catch (Exception error) {
        runOnUiThread(() -> { if (!isDestroyed()) result.setText("检查失败：" + NativeEngine.message(error) + "\n可稍后重试或打开 GitHub 发布页面。"); });
      } finally {
        runOnUiThread(() -> {
          checking = false;
          if (!isDestroyed()) { check.setEnabled(true); check.setText("检查 GitHub 更新"); this.previews.setEnabled(true); }
        });
      }
    });
  }
  private void show(JSONObject found) {
    if (found.optString("status").isEmpty()) return;
    try {
      if (found.optString("status").equals("available") && ReleaseVersions.compare(found.optString("version"), AppUpdater.version(this)) <= 0)
        found.put("status", "current").put("message", "当前已是最新版本（" + AppUpdater.version(this) + "）");
    } catch (Exception ignored) {}
    release = found;
    String time = found.optLong("checkedAt") > 0 ? "\n检查时间：" + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(found.optLong("checkedAt"))) : "";
    result.setText(found.optString("message") + time);
    String text = found.optString("notes");
    notes.setText(text.isEmpty() ? "" : "更新说明\n" + text);
    download.setVisibility(found.optString("status").equals("available") ? android.view.View.VISIBLE : android.view.View.GONE);
    if (found.optJSONObject("asset") != null) download.setText("下载并安装 " + found.optString("version") + " · "
        + String.format(java.util.Locale.ROOT, "%.1f MB", found.optJSONObject("asset").optLong("size") / 1048576.0));
  }
  private void download() {
    if (release == null || !release.optString("status").equals("available") || AppUpdateService.active()) return;
    try {
      autoInstall = true;
      AppUpdater.state(this, "downloading", "正在开始下载…");
      startForegroundService(new Intent(this, AppUpdateService.class).putExtra("release", release.toString()));
      download.setEnabled(false); refreshDownload();
    } catch (Exception error) {
      autoInstall = false;
      try { AppUpdater.state(this, "failed", NativeEngine.message(error)); } catch (Exception ignored) {}
      NativeUi.error(this, "无法启动下载：" + NativeEngine.message(error));
    }
  }
  private void refreshDownload() {
    JSONObject state = AppUpdater.state(this);
    String status = state.optString("status");
    progress.setText(state.optString("message"));
    download.setEnabled(!status.equals("downloading"));
    boolean ready = status.equals("ready") && AppUpdater.apk(this).isFile();
    install.setVisibility(ready ? android.view.View.VISIBLE : android.view.View.GONE);
    if (status.equals("failed")) autoInstall = false;
    if (ready && autoInstall && !installing && hasWindowFocus()) { autoInstall = false; install(); }
  }
  private void install() {
    if (installing) return;
    installing = true; install.setEnabled(false);
    worker.execute(() -> {
      try {
        AppUpdater.verifyApk(this, AppUpdater.apk(this));
        runOnUiThread(() -> {
          if (isDestroyed()) return;
          installing = false; install.setEnabled(true);
          try {
            if (!getPackageManager().canRequestPackageInstalls()) {
              startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())), 81);
              return;
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", AppUpdater.apk(this));
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
          } catch (Exception error) { NativeUi.error(this, "无法打开安装程序：" + NativeEngine.message(error)); }
        });
      } catch (Exception error) {
        try { AppUpdater.state(this, "failed", NativeEngine.message(error)); } catch (Exception ignored) {}
        runOnUiThread(() -> { installing = false; if (!isDestroyed()) { install.setEnabled(true); refreshDownload(); NativeUi.error(this, NativeEngine.message(error)); } });
      }
    });
  }
  @Override protected void onActivityResult(int request, int resultCode, Intent data) {
    super.onActivityResult(request, resultCode, data);
    if (request == 81) {
      if (getPackageManager().canRequestPackageInstalls()) install();
      else NativeUi.error(this, "允许 Ai-XS 安装应用后，可再次点击安装更新");
    }
  }
  @Override protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putBoolean("autoInstall", autoInstall);
    if (release != null) state.putString("release", release.toString());
  }
  @Override protected void onResume() { super.onResume(); handler.post(refresh); }
  @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
  @Override protected void onDestroy() { handler.removeCallbacks(refresh); worker.shutdown(); super.onDestroy(); }
}
