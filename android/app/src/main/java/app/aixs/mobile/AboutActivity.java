package app.aixs.mobile;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
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
  private Button check, download, install, viewLogs;
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
    LinearLayout root = NativeUi.page(this, "关于", this::finish);
    LinearLayout body = NativeUi.scroll(this, root);
    brand(body);

    NativeUi.section(this, body, "应用信息");
    LinearLayout info = Ui.group(body);
    valueRow(info, "版本", AppUpdater.version(this));
    valueRow(info, "构建", buildNumber());
    valueRow(info, "应用包", getPackageName());
    valueRow(info, "Agent", "Codex · Claude Code · DeepSeek");

    NativeUi.section(this, body, "项目");
    LinearLayout links = Ui.group(body);
    Ui.rowItem(links, R.drawable.ic_code, "GitHub 项目", "LXiangSong / Ai-XS",
        () -> open(ReleaseVersions.REPOSITORY));
    Ui.rowItem(links, R.drawable.ic_log, "发布记录", "查看历史版本与更新说明",
        () -> open(ReleaseVersions.REPOSITORY + "/releases"));
    Ui.rowItem(links, R.drawable.ic_chat, "反馈问题", "在 GitHub Issues 提交问题与建议",
        () -> open(ReleaseVersions.REPOSITORY + "/issues"));

    NativeUi.section(this, body, "应用更新");
    LinearLayout update = Ui.group(body);
    previews = new com.google.android.material.materialswitch.MaterialSwitch(this);
    previews.setChecked(getPreferences(0).getBoolean("previews", ReleaseVersions.prerelease(AppUpdater.version(this))));
    previews.setOnCheckedChangeListener((button, checked) -> {
      getPreferences(0).edit().putBoolean("previews", checked).apply();
      new File(AppUpdater.folder(this), "check.json").delete();
      release = null;
      download.setVisibility(View.GONE);
      result.setText("更新渠道已切换，请重新检查");
      notes.setText("");
    });
    switchRow(update, "包含测试版更新", "接收 GitHub Pre-release 版本", previews);

    check = NativeUi.textButton(this, "检查 GitHub 更新", () -> check(previews.isChecked()));
    check.setTextColor(Ui.accent);
    inset(update).addView(check);

    result = Ui.text(this, "点击检查更新，获取 GitHub Releases 中的最新版本。", 12.5f, Ui.muted, null);
    result.setLineSpacing(dp(3), 1f);
    result.setPadding(dp(14), dp(6), dp(14), dp(8));
    update.addView(result);

    download = NativeUi.outlinedButton(this, "下载并安装更新", this::download);
    download.setVisibility(View.GONE);
    inset(update).addView(download);

    progress = Ui.text(this, "", 11.5f, Ui.faint, null);
    progress.setLineSpacing(dp(2), 1f);
    progress.setPadding(dp(14), dp(2), dp(14), dp(2));
    update.addView(progress);

    install = NativeUi.primaryButton(this, "安装已下载更新", this::install);
    install.setVisibility(View.GONE);
    inset(update).addView(install);

    viewLogs = NativeUi.textButton(this, "失败详情 · 查看运行日志", () -> startActivity(
        new Intent(this, RuntimeActivity.class).putExtra("page", "logs")
            .putExtra("returnToCaller", true).putExtra("debug", true).putExtra("logSource", "app-update")));
    viewLogs.setTextColor(Ui.muted);
    viewLogs.setVisibility(View.GONE);
    inset(update, 0, 10).addView(viewLogs);

    notes = Ui.text(this, "", 12.5f, Ui.muted, null);
    notes.setTextIsSelectable(true);
    notes.setLineSpacing(dp(3), 1f);
    inset(update, 6, 4).addView(notes);

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

  private int dp(int n) { return Ui.dp(this, n); }

  private void brand(LinearLayout body) {
    LinearLayout hero = Ui.group(body);
    LinearLayout row = Ui.row(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    Ui.pad(row, 14, 16, 14, 16);
    LinearLayout mark = new LinearLayout(this);
    mark.setGravity(Gravity.CENTER);
    mark.setBackground(Ui.round(Ui.accentSoft, 14));
    ImageView icon = new ImageView(this);
    icon.setImageResource(R.drawable.ic_spark);
    icon.setColorFilter(Ui.accent);
    mark.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
    row.addView(mark, new LinearLayout.LayoutParams(dp(48), dp(48)));
    LinearLayout labels = Ui.col(this);
    LinearLayout.LayoutParams labelSpace = new LinearLayout.LayoutParams(0, -2, 1f);
    labelSpace.leftMargin = dp(12);
    row.addView(labels, labelSpace);
    labels.addView(Ui.text(this, "Ai-XS", 21, Ui.text, Ui.medium(this)));
    TextView tagline = Ui.text(this, "手机上的原生 AI 编程工作台", 12.5f, Ui.muted, null);
    LinearLayout.LayoutParams taglineSpace = new LinearLayout.LayoutParams(-1, -2);
    taglineSpace.topMargin = dp(2);
    labels.addView(tagline, taglineSpace);
    TextView version = Ui.text(this, AppUpdater.version(this), 11, Ui.onAccentSoft, Ui.medium(this));
    version.setPadding(dp(9), dp(4), dp(9), dp(4));
    version.setBackground(Ui.round(Ui.accentSoft, 999));
    row.addView(version);
    hero.addView(row);

    TextView intro = Ui.text(this,
        "三个 Agent 共用运行环境，可切换接手同一项目与会话。支持官方及第三方 API、Skills、插件与 MCP 扩展。",
        13, Ui.muted, null);
    intro.setLineSpacing(dp(3), 1f);
    LinearLayout.LayoutParams introSpace = new LinearLayout.LayoutParams(-1, -2);
    introSpace.leftMargin = dp(4);
    introSpace.rightMargin = dp(4);
    introSpace.topMargin = dp(2);
    body.addView(intro, introSpace);
  }

  private void valueRow(LinearLayout group, String title, String value) {
    if (group.getChildCount() > 0) group.addView(Ui.divider(this, 14));
    LinearLayout row = Ui.row(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    Ui.pad(row, 14, 11, 14, 11);
    row.addView(Ui.text(this, title, 13.5f, Ui.muted, null),
        new LinearLayout.LayoutParams(0, -2, 1f));
    TextView text = Ui.text(this, value, 12.5f, Ui.text, Ui.medium(this));
    text.setGravity(Gravity.END);
    text.setMaxLines(2);
    row.addView(text);
    group.addView(row);
  }

  private void switchRow(LinearLayout group, String title, String detail, CompoundButton toggle) {
    if (group.getChildCount() > 0) group.addView(Ui.divider(this, 14));
    LinearLayout row = Ui.row(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    Ui.pad(row, 14, 9, 10, 9);
    LinearLayout labels = Ui.col(this);
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
    labels.addView(Ui.text(this, title, 14, Ui.text, Ui.medium(this)));
    TextView note = Ui.text(this, detail, 11.5f, Ui.muted, null);
    LinearLayout.LayoutParams noteSpace = new LinearLayout.LayoutParams(-1, -2);
    noteSpace.topMargin = dp(2);
    labels.addView(note, noteSpace);
    row.addView(toggle);
    group.addView(row);
  }

  private LinearLayout inset(LinearLayout group) { return inset(group, 6, 4); }

  private LinearLayout inset(LinearLayout group, int top, int bottom) {
    LinearLayout box = Ui.col(this);
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
    space.leftMargin = dp(14);
    space.rightMargin = dp(14);
    space.topMargin = dp(top);
    space.bottomMargin = dp(bottom);
    group.addView(box, space);
    return box;
  }

  private String buildNumber() {
    try {
      PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
      long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
      return String.valueOf(code);
    } catch (Exception ignored) {
      return "—";
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
    download.setVisibility(View.GONE); release = null;
    result.setText("正在连接 GitHub Releases…"); notes.setText(""); viewLogs.setVisibility(View.GONE);
    AppLogs.info(this, "app-update", "检查 GitHub 应用更新");
    worker.execute(() -> {
      try {
        JSONObject found = UpdateSources.app(AppUpdater.version(this), previews);
        RuntimeManager.write(new File(AppUpdater.folder(this), "check.json"), found.toString());
        AppLogs.info(this, "app-update", found.optString("message"));
        runOnUiThread(() -> { if (!isDestroyed()) show(found); });
      } catch (Exception error) {
        AppLogs.failure(this, "app-update", "", "检查应用更新失败", error);
        runOnUiThread(() -> { if (!isDestroyed()) { result.setText("检查失败：" + Diagnostics.redact(this, NativeEngine.message(error)) + "\n详情请查看运行日志。"); viewLogs.setVisibility(View.VISIBLE); } });
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
    download.setVisibility(found.optString("status").equals("available") ? View.VISIBLE : View.GONE);
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
      updateFailure("无法启动下载", error);
    }
  }

  private void refreshDownload() {
    JSONObject state = AppUpdater.state(this);
    String status = state.optString("status");
    progress.setText(state.optString("message"));
    download.setEnabled(!status.equals("downloading"));
    boolean ready = status.equals("ready") && AppUpdater.apk(this).isFile();
    install.setVisibility(ready ? View.VISIBLE : View.GONE);
    if (status.equals("failed")) { autoInstall = false; viewLogs.setVisibility(View.VISIBLE); }
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
          } catch (Exception error) { updateFailure("无法打开安装程序", error); }
        });
      } catch (Exception error) {
        AppLogs.failure(this, "app-update", "", "安装包验证失败", error);
        try { AppUpdater.state(this, "failed", NativeEngine.message(error)); } catch (Exception ignored) {}
        runOnUiThread(() -> { installing = false; if (!isDestroyed()) { install.setEnabled(true); refreshDownload(); viewLogs.setVisibility(View.VISIBLE); } });
      }
    });
  }

  private void updateFailure(String message, Exception error) {
    AppLogs.failure(this, "app-update", "", message, error);
    progress.setText(message + "：" + Diagnostics.redact(this, NativeEngine.message(error)) + "\n详情请查看运行日志。");
    viewLogs.setVisibility(View.VISIBLE);
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
