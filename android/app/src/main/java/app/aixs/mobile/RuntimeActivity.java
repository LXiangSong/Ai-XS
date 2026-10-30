package app.aixs.mobile;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Settings remain available before the shared runtime is initialized. */
public final class RuntimeActivity extends androidx.appcompat.app.AppCompatActivity {
  private LinearLayout body, operationCard;
  private TextView operationTitle, operationMessage;
  private ProgressBar operationProgress;
  private Button operationLogs;
  private JSONObject pendingOperation;
  private String logChannel = "normal", logSource = "all", logOperation = "";
  private final java.util.Map<String, TextView> agentLabels = new java.util.HashMap<>();
  private boolean exporting;
  private TextView summary, logs, logCount;
  private Button latestLogs;
  private final java.util.HashMap<String, LinearLayout> logChips = new java.util.HashMap<>();
  private ScrollView logScroll;
  private String rawLog = "";
  private boolean forceLogRead = true, logAuto = true, logFollowing = true, logTouch = false, logReading = false;
  private long logLength = -1, logModified = -1;
  private int pageGeneration;
  private String displayedLog = "";
  private final ExecutorService logReader = Executors.newSingleThreadExecutor();
  private final ExecutorService updateReader = Executors.newSingleThreadExecutor();
  private boolean checkingAgents;
  private String logFilter = "all", logQuery = "";
  private RuntimeManager manager;
  private String page = "settings", selectedAgent;
  private final java.util.ArrayList<String> history = new java.util.ArrayList<>();
  private final Handler handler = new Handler();
  private final Runnable refresh = new Runnable() {
    public void run() {
      update();
      handler.postDelayed(this, 1500);
    }
  };
  private int dp(int n) {
    return (int) (n * getResources().getDisplayMetrics().density + .5f);
  }
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    manager = new RuntimeManager(this, s -> {});
    page = getIntent().getStringExtra("page");
    if (page == null)
      page = "settings";
    logSource = getIntent().getStringExtra("logSource") == null ? "all" : getIntent().getStringExtra("logSource");
    logChannel = getIntent().getBooleanExtra("debug", false) ? "debug" : "normal";
    if (state != null) {
      page = state.getString("page", "settings");
      selectedAgent = state.getString("agent");
      logChannel = state.getString("logChannel", "normal");
      logSource = state.getString("logSource", "all");
      logOperation = state.getString("logOperation", "");
      logFilter = state.getString("logFilter", "all");
      logQuery = state.getString("logQuery", "");
      java.util.ArrayList<String> saved = state.getStringArrayList("history");
      if (saved != null) history.addAll(saved);
    } else if (!page.equals("settings") && !getIntent().getBooleanExtra("returnToCaller", false)) history.add("settings");
    if (page.equals("api")) {
      page = "settings";
      history.clear();
      startActivity(new Intent(this, NativeToolsActivity.class).putExtra("page", "api"));
    }
    getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
      @Override
      public void handleOnBackPressed() {
        back();
      }
    });
    render();
  }
  private void text(String value, int size) {
    TextView v = NativeUi.text(this, value, size, NativeUi.TEXT);
    if (size >= 20)
      v.setTypeface(null, android.graphics.Typeface.BOLD);
    body.addView(v);
  }
  private Button button(String value, Runnable action) {
    Button b = NativeUi.outlinedButton(this, value, action);
    body.addView(b);
    return b;
  }
  private Button primaryButton(String value, Runnable action) {
    Button b = NativeUi.primaryButton(this, value, action);
    body.addView(b);
    return b;
  }
  private Button textButton(String value, Runnable action) {
    Button b = NativeUi.textButton(this, value, action);
    body.addView(b);
    return b;
  }
  private void render() {
    summary = null;
    operationCard = null;
    agentLabels.clear();
    logs = null;
    logScroll = null;
    logCount = null;
    latestLogs = null;
    logChips.clear();
    pageGeneration++;
    LinearLayout root = NativeUi.page(this, page.equals("logs") ? "运行日志" : "", this::back);
    if (!page.equals("logs")) createOperationCard(root);
    if (page.equals("logs")) {
      body = Ui.col(this);
      body.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 8));
      root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
    } else body = NativeUi.scroll(this, root);
    if (page.equals("settings"))
      settingsPage();
    else if (page.equals("environment"))
      environmentPage();
    else if (page.equals("agents"))
      agentsPage();
    else if (page.equals("device"))
      devicePage();
    else
      logsPage();
    update();
  }

  private void settingsPage() {
    NativeUi.heading(this, body, "设置", "管理服务、运行环境与手机能力。");
    statusView();

    NativeUi.section(this, body, "服务");
    NativeUi.tile(this, body, R.drawable.ic_key, "API 服务配置", "官方 API / 第三方 API，三个 Agent 独立配置",
        () -> startActivity(new Intent(this, NativeToolsActivity.class).putExtra("page", "api")));
    NativeUi.tile(this, body, R.drawable.ic_cloud, "运行环境",
        manager.complete() ? "已就绪 · 可检查与修复" : manager.initialized() ? "基础系统已就绪 · 待补装组件" : "未初始化",
        () -> show("environment"));
    NativeUi.tile(this, body, R.drawable.ic_spark, "Agent 安装", installedCount() + " / 3 已安装",
        () -> show("agents"));

    NativeUi.section(this, body, "能力");
    NativeUi.tile(this, body, R.drawable.ic_device, "手机与开发工具",
        PhoneAccessibilityService.active != null ? "无障碍已开启 · 页面调试可配置" : "无障碍未开启 · 页面调试可配置",
        () -> show("device"));
    NativeUi.tile(this, body, R.drawable.ic_tools, "工具与扩展", "MCP 连接、GitHub 扩展与本地导入",
        () -> startActivity(new Intent(this, NativeToolsActivity.class).putExtra("engine", "codex")));
    NativeUi.tile(this, body, R.drawable.ic_log, "运行日志", "普通事件、Debug 诊断与报告导出", this::openLogs);

    NativeUi.section(this, body, "维护");
    NativeUi.tile(this, body, R.drawable.ic_check, "检查 Agent 接口", "检查版本、模型与工具连接",
        () -> operate("agent-check", null));
    NativeUi.tile(this, body, R.drawable.ic_stop, "停止 Agent 服务", busy() ? "当前可能有任务在运行" : "服务空闲",
        this::confirmStop);
    NativeUi.tile(this, body, R.drawable.ic_spark, "Agent 更新", "检查 Codex、Claude Code、DeepSeek 最新版本",
        () -> checkAgentUpdates(null));
    NativeUi.tile(this, body, R.drawable.ic_check, "关于 Ai-XS", "版本信息、GitHub 项目与应用更新",
        () -> startActivity(new Intent(this, AboutActivity.class)));

    View spacer = new View(this);
    LinearLayout.LayoutParams spacerSpace = new LinearLayout.LayoutParams(1, Ui.dp(this, 12));
    body.addView(spacer, spacerSpace);
    TextView version = Ui.text(this, "Ai-XS " + versionName() + " · 原生 Android", 11.5f, Ui.faint, null);
    version.setGravity(android.view.Gravity.CENTER);
    body.addView(version);
  }

  private void environmentPage() {
    NativeUi.heading(this, body, "运行环境",
        "三个 Agent 共用一份 Linux、Python、Node 和 Git。首次初始化需要联网下载基础工具，"
            + "以后更新 App 会保留环境，修复只补装缺失组件。");
    statusView();

    NativeUi.section(this, body, "当前状态");
    boolean ready = manager.complete();
    NativeUi.tile(this, body, R.drawable.ic_cloud, "基础系统", ready ? "已初始化" : manager.initialized() ? "已就绪，待补装组件" : "未初始化",
        null);
    NativeUi.tile(this, body, R.drawable.ic_terminal, "共享组件", ready ? "Python · Node · Git 已就绪" : "等待安装", null);
    NativeUi.tile(this, body, R.drawable.ic_spark, "Agent 运行时", installedCount() + " / 3 已安装", () -> show("agents"));

    NativeUi.section(this, body, "操作");
    body.addView(NativeUi.primaryButton(this, ready ? "修复 / 补装组件" : "初始化环境",
        () -> operate("initialize", null)));
    NativeUi.tile(this, body, R.drawable.ic_check, "检查环境", "核对组件与版本", () -> operate("check", null));
    NativeUi.tile(this, body, R.drawable.ic_log, "运行日志", "普通事件与 Debug 诊断", this::openLogs);
  }

  private void agentsPage() {
    NativeUi.heading(this, body, "Agent 安装与更新",
        "三个 Agent 共用环境。可检查官方最新版本，也可从 GitHub 或本地导入 Ai-XS ARM64 部署包，安装后保留会话、项目与 API 配置。");
    statusView();
    primaryButton("检查全部 Agent 更新", () -> checkAgentUpdates(null));

    NativeUi.section(this, body, "Agent");
    LinearLayout agents = Ui.group(body);
    for (String id : new String[] {"codex", "claude", "deepseek"}) {
      LinearLayout row = Ui.rowItem(agents, R.drawable.ic_spark, agentName(id),
          installed(id) ? manager.installed(id) + " · 点击管理" : "未安装 · 点击安装", () -> agentSheet(id));
      agentLabels.put(id, (TextView) ((LinearLayout) row.getChildAt(1)).getChildAt(1));
    }

    NativeUi.section(this, body, "诊断");
    NativeUi.tile(this, body, R.drawable.ic_check, "检查 Agent 接口", "版本、模型与工具连接",
        () -> operate("agent-check", null));
    NativeUi.tile(this, body, R.drawable.ic_log, "运行日志", "普通事件与 Debug 诊断", this::openLogs);
  }

  private void devicePage() {
    boolean accessibility = PhoneAccessibilityService.active != null;
    NativeUi.heading(this, body, "手机与开发工具",
        "无障碍授权后，三个 Agent 共用屏幕读取、点击、输入、滑动与截图工具，可随时在系统设置关闭。");
    statusView();

    NativeUi.section(this, body, "手机控制");
    NativeUi.tile(this, body, R.drawable.ic_device, "无障碍服务", accessibility ? "已开启" : "未开启 · 点击授权",
        this::openAccessibility);

    NativeUi.section(this, body, "开发浏览器");
    switchRow("允许 Agent 调试页面", "开启后 Agent 可读取 DOM、运行 JavaScript、截图。",
        getSharedPreferences("device", 0).getBoolean("browser_tools", false),
        on -> getSharedPreferences("device", 0).edit().putBoolean("browser_tools", on).apply());
    NativeUi.tile(this, body, R.drawable.ic_code, "打开开发浏览器", "预览与调试项目页面",
        () -> startActivity(new Intent(this, DeveloperBrowserActivity.class)));
    NativeUi.tile(this, body, R.drawable.ic_log, "浏览器日志", "查看页面工具的输出", this::browserLog);

    NativeUi.section(this, body, "诊断");
    NativeUi.tile(this, body, R.drawable.ic_check, "检查内置工具连接", "无障碍、页面调试与文件访问",
        () -> operate("device-check", null));
  }

  private void logsPage() {
    LinearLayout modes = Ui.row(this);
    for (String mode : new String[] {"normal", "debug", "legacy"}) {
      String label = mode.equals("normal") ? "普通日志" : mode.equals("debug") ? "Debug" : "历史日志";
      Button tab = logChannel.equals(mode) ? NativeUi.primaryButton(this, label, () -> changeLogChannel(mode))
          : NativeUi.textButton(this, label, () -> changeLogChannel(mode));
      modes.addView(tab, new LinearLayout.LayoutParams(0, -2, 1f));
    }
    body.addView(modes);
    TextView hint = NativeUi.text(this, logChannel.equals("debug") ? "底层命令输出、错误堆栈与浏览器诊断 · 自动脱敏"
        : logChannel.equals("normal") ? "操作阶段、完成结果与错误提醒" : "升级前保留的旧版输出", 12, NativeUi.MUTED);
    body.addView(hint);
    Spinner sources = new Spinner(this);
    String[] sourceLabels = {"全部来源", "运行环境", "Agent 服务", "应用更新", "浏览器", "应用崩溃"};
    String[] sourceValues = {"all", "runtime", "backend", "app-update", "browser", "app"};
    sources.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, sourceLabels));
    for (int i = 0; i < sourceValues.length; i++) if (sourceValues[i].equals(logSource)) sources.setSelection(i);
    sources.setEnabled(!logChannel.equals("legacy"));
    sources.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      public void onNothingSelected(AdapterView<?> parent) {}
      public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (!logSource.equals(sourceValues[position])) { logSource = sourceValues[position]; renderLogs(); }
      }
    });
    body.addView(sources);
    if (!logOperation.isEmpty()) {
      TextView logScope = NativeUi.text(this, "仅此操作 #" + logOperation, 12, NativeUi.ACCENT);
      body.addView(logScope);
      body.addView(NativeUi.textButton(this, "查看所有操作", () -> { logOperation = ""; render(); }));
    }

    LinearLayout toolbar = Ui.group(body);
    LinearLayout followRow = Ui.row(this);
    followRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
    Ui.pad(followRow, 14, 10, 14, 10);
    LinearLayout followLabels = Ui.col(this);
    followRow.addView(followLabels, new LinearLayout.LayoutParams(0, -2, 1f));
    followLabels.addView(Ui.text(this, "自动刷新", 14.5f, Ui.text, Ui.medium(this)));
    followLabels.addView(Ui.text(this, "查看旧内容时暂停跟随", 11.5f, Ui.muted, null));
    com.google.android.material.materialswitch.MaterialSwitch auto =
        new com.google.android.material.materialswitch.MaterialSwitch(this);
    auto.setChecked(logAuto);
    auto.setOnCheckedChangeListener((b, on) -> {
      logAuto = on;
      if (on) update();
      updateLogCount();
    });
    followRow.addView(auto);
    toolbar.addView(followRow);

    LinearLayout filters = Ui.row(this);
    filters.setGravity(android.view.Gravity.CENTER_VERTICAL);
    Ui.pad(filters, 12, 8, 12, 10);
    filters.addView(logChip("全部", "all"));
    filters.addView(logChip("警告", "warn"));
    filters.addView(logChip("错误", "error"));
    toolbar.addView(filters);

    EditText search = new EditText(this);
    search.setSingleLine(true);
    search.setHint("过滤关键词");
    search.setTextSize(13.5f);
    search.setTextColor(Ui.text);
    search.setHintTextColor(Ui.faint);
    search.setText(logQuery);
    search.setBackground(Ui.round(this, Ui.cardHigh, 12, 0.8f, Ui.lineSoft));
    search.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
    LinearLayout.LayoutParams searchSpace = new LinearLayout.LayoutParams(-1, -2);
    searchSpace.leftMargin = Ui.dp(this, 12);
    searchSpace.rightMargin = Ui.dp(this, 12);
    searchSpace.topMargin = Ui.dp(this, 2);
    searchSpace.bottomMargin = Ui.dp(this, 10);
    body.addView(search, searchSpace);
    search.addTextChangedListener(new android.text.TextWatcher() {
      public void afterTextChanged(android.text.Editable value) {
        logQuery = value.toString();
        renderLogs();
      }
      public void beforeTextChanged(CharSequence v, int a, int b, int c) {}
      public void onTextChanged(CharSequence v, int a, int b, int c) {}
    });

    logs = Ui.text(this, "暂无日志", 11.5f, Ui.text, android.graphics.Typeface.MONOSPACE);
    logs.setTextIsSelectable(true);
    logs.setLineSpacing(Ui.dp(this, 3), 1f);
    logs.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 14));
    logs.setBackground(Ui.round(this, Ui.cardHigh, 14, 0.8f, Ui.lineSoft));
    ScrollView box = new ScrollView(this);
    box.setId(R.id.runtime_log_scroll);
    box.addView(logs);
    box.setBackground(Ui.round(this, Ui.cardHigh, 14, 0.8f, Ui.lineSoft));
    box.setPadding(0, 0, 0, 0);
    logScroll = box;
    View.OnTouchListener logTouchListener = (v, event) -> {
      if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
        logTouch = true;
        if (box.canScrollVertically(-1) || box.canScrollVertically(1)) logFollowing = false;
        updateLogCount();
      } else if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP
          || event.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) logTouch = false;
      return false;
    };
    box.setOnTouchListener(logTouchListener);
    logs.setOnTouchListener(logTouchListener);
    LinearLayout.LayoutParams boxSpace = new LinearLayout.LayoutParams(-1, 0, 1f);
    boxSpace.leftMargin = Ui.dp(this, 12);
    boxSpace.rightMargin = Ui.dp(this, 12);
    body.addView(box, boxSpace);
    logs.setBackground(null);

    LinearLayout.LayoutParams countSpace = new LinearLayout.LayoutParams(-1, -2);
    countSpace.leftMargin = Ui.dp(this, 16);
    countSpace.topMargin = Ui.dp(this, 6);
    logCount = Ui.text(this, "", 11.5f, Ui.faint, null);
    body.addView(logCount, countSpace);

    LinearLayout actions = Ui.row(this);
    actions.addView(NativeUi.textButton(this, "复制日志", this::copyLogs), new LinearLayout.LayoutParams(0, -2, 1f));
    latestLogs = NativeUi.textButton(this, "跳到最新", () -> {
      logFollowing = true;
      renderLogs();
      scrollLogsToEnd();
    });
    actions.addView(latestLogs, new LinearLayout.LayoutParams(0, -2, 1f));
    body.addView(actions);
    body.addView(NativeUi.textButton(this, "导出诊断报告", this::exportLogs));
    renderLogs();
  }

  private String filteredLogs() {
    return RuntimeLogText.filter(rawLog, logFilter, logQuery, logChannel.equals("legacy") ? "all" : logSource, logOperation);
  }
  private void changeLogChannel(String mode) {
    if (logChannel.equals(mode)) return;
    logChannel = mode;
    if (mode.equals("legacy")) { logSource = "all"; logOperation = ""; }
    resetLogSnapshot(); render();
  }
  private void exportLogs() {
    if (exporting) { toast("正在导出，请稍候"); return; }
    Intent pick = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType("text/plain").putExtra(Intent.EXTRA_TITLE, "Ai-XS-diagnostics-" + System.currentTimeMillis() + ".txt");
    try { startActivityForResult(pick, 72); } catch (Exception error) { toast("无法打开文件保存页面"); }
  }

  private LinearLayout logChip(String label, String value) {
    boolean active = logFilter.equals(value);
    LinearLayout chip = Ui.row(this);
    chip.setGravity(android.view.Gravity.CENTER);
    chip.setBackground(Ui.round(this, active ? Ui.accentSoft : Ui.cardHigh, 999, 0, Ui.lineSoft));
    Ui.pad(chip, 12, 0, 12, 0);
    chip.setMinimumHeight(Ui.dp(this, 30));
    chip.addView(Ui.text(this, label, 12.5f, active ? Ui.onAccentSoft : Ui.muted, Ui.medium(this)));
    Ui.touchable(chip);
    Ui.rippleRounded(chip, 999, Ui.accent);
    chip.setOnClickListener(v -> {
      logFilter = value;
      renderLogs();
      updateLogChips();
    });
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
    space.rightMargin = Ui.dp(this, 8);
    chip.setLayoutParams(space);
    logChips.put(value, chip);
    return chip;
  }

  private void updateLogChips() {
    for (java.util.Map.Entry<String, LinearLayout> entry : logChips.entrySet()) {
      boolean active = entry.getKey().equals(logFilter);
      entry.getValue().setBackground(Ui.round(this, active ? Ui.accentSoft : Ui.cardHigh, 999, 0, Ui.lineSoft));
      ((TextView) entry.getValue().getChildAt(0)).setTextColor(active ? Ui.onAccentSoft : Ui.muted);
    }
  }

  private void renderLogs() {
    if (logs == null) return;
    String value = filteredLogs();
    String shown = value.isEmpty() ? "没有匹配的日志" : value;
    if (!shown.contentEquals(logs.getText())) {
      if (!displayedLog.isEmpty() && value.startsWith(displayedLog) && logs.getText().toString().equals(displayedLog))
        logs.append(value.substring(displayedLog.length()));
      else logs.setText(shown);
      displayedLog = value;
      if (logFollowing) scrollLogsToEnd();
    }
    updateLogCount();
  }

  private void updateLogCount() {
    if (logCount == null) return;
    boolean pending = !filteredLogs().equals(displayedLog);
    String state = !logAuto ? "刷新已暂停" : !logFollowing ? "正在查看历史" : "跟随最新";
    logCount.setText(state + " · 显示 " + RuntimeLogText.lines(displayedLog) + " 行 · 最近 64 KB"
        + (pending ? " · 有新日志" : ""));
    if (latestLogs != null) latestLogs.setText(pending ? "有新日志 · 跳到最新" : "跳到最新");
  }

  private void scrollLogsToEnd() {
    final ScrollView target = logScroll;
    if (target == null) return;
    target.post(() -> {
      if (target != logScroll || !logFollowing || logTouch) return;
      target.scrollTo(0, Math.max(0, target.getChildAt(0).getHeight() - target.getHeight()
          + target.getPaddingTop() + target.getPaddingBottom()));
    });
  }

  private void readLogs() {
    if (logs == null || (!logAuto && !forceLogRead) || logReading) return;
    File log = logChannel.equals("legacy") ? new File(getFilesDir(), "runtime.log") : AppLogs.file(this, logChannel.equals("debug"));
    long length = log.length(), modified = log.lastModified();
    if (length == logLength && modified == logModified) return;
    logReading = true;
    final boolean forced = forceLogRead; forceLogRead = false;
    final int generation = pageGeneration;
    logReader.execute(() -> {
      String content = null;
      try { content = RuntimeLogText.read(log, 64 * 1024); } catch (IOException ignored) {}
      final String value = content;
      handler.post(() -> {
        logReading = false;
        if (generation != pageGeneration || logs == null || (!logAuto && !forced) || isDestroyed()) return;
        if (value != null) {
          logLength = length;
          logModified = modified;
          rawLog = value;
          if (logFollowing) renderLogs(); else updateLogCount();
        }
      });
    });
  }

  private void copyLogs() {
    android.content.ClipboardManager clipboard =
        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    if (clipboard == null) return;
    CharSequence value = Diagnostics.redact(this, displayedLog);
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Ai-XS", value));
    toast("日志已复制");
  }

  /** Row with a trailing switch, used for device toggles. */
  private void switchRow(String title, String detail, boolean checked, java.util.function.Consumer<Boolean> change) {
    LinearLayout group = Ui.group(body);
    LinearLayout row = Ui.row(this);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    Ui.pad(row, 14, 12, 14, 12);
    LinearLayout labels = Ui.col(this);
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
    labels.addView(Ui.text(this, title, 15, Ui.text, Ui.medium(this)));
    TextView note = Ui.text(this, detail, 12.5f, Ui.muted, null);
    note.setLineSpacing(Ui.dp(this, 2), 1f);
    LinearLayout.LayoutParams noteSpace = new LinearLayout.LayoutParams(-1, -2);
    noteSpace.topMargin = Ui.dp(this, 3);
    labels.addView(note, noteSpace);
    com.google.android.material.materialswitch.MaterialSwitch toggle =
        new com.google.android.material.materialswitch.MaterialSwitch(this);
    toggle.setChecked(checked);
    toggle.setOnCheckedChangeListener((b, on) -> change.accept(on));
    LinearLayout.LayoutParams toggleSpace = new LinearLayout.LayoutParams(-2, -2);
    toggleSpace.leftMargin = Ui.dp(this, 10);
    row.addView(toggle, toggleSpace);
    group.addView(row);
  }

  private void openAccessibility() {
    try {
      Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                          .putExtra(Intent.EXTRA_COMPONENT_NAME,
                              new android.content.ComponentName(this, PhoneAccessibilityService.class));
      try {
        startActivity(details);
      } catch (SecurityException unavailable) {
        startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
      }
    } catch (Exception e) {
      toast("无法打开系统设置");
    }
  }

  private void browserLog() {
    logChannel = "debug"; logSource = "browser"; logOperation = "";
    resetLogSnapshot(); show("logs");
  }

  private void confirmStop() {
    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle("停止 Agent 服务？")
        .setMessage(busy() ? "仍有任务在运行，停止会中断当前工作。" : "服务将停止，下次发送消息时会重新启动。")
        .setNegativeButton("取消", null)
        .setPositiveButton("停止", (d, w) -> operate("stop", null))
        .show();
  }

  private boolean busy() {
    try {
      File status = new File(getFilesDir(), "runtime-operation.json");
      return status.isFile() && "running".equals(RuntimeManager.json(status).optString("status"));
    } catch (Exception ignored) {
      return false;
    }
  }

  private boolean installed(String id) {
    return !"未安装".equals(manager.installed(id));
  }

  private int installedCount() {
    int count = 0;
    for (String id : new String[] {"codex", "claude", "deepseek"})
      if (installed(id)) count++;
    return count;
  }

  private void agentSheet(String id) {
    selectedAgent = id;
    LinearLayout box = Ui.col(this);
    box.setPadding(Ui.dp(this, 18), Ui.dp(this, 4), Ui.dp(this, 18), Ui.dp(this, 4));
    LinearLayout group = Ui.group(box);
    if (installed(id)) Ui.rowItem(group, R.drawable.ic_history, "版本管理 / 回退", "选择使用的版本，保留或删除旧版本", () -> {
      agentDialogDismiss();
      agentVersions(id);
    });
    Ui.rowItem(group, R.drawable.ic_check, "检查官方更新", installed(id) ? "当前 " + manager.installed(id) : "检查最新可安装版本", () -> {
      agentDialogDismiss();
      checkAgentUpdates(id);
    });
    Ui.rowItem(group, R.drawable.ic_cloud, "从官方来源安装", "固定版本发布包", () -> {
      agentDialogDismiss();
      operate("agent", id);
    });
    Ui.rowItem(group, R.drawable.ic_code, "从 GitHub Releases 安装", "需要部署包链接与 SHA-256", () -> {
      agentDialogDismiss();
      github(id);
    });
    Ui.rowItem(group, R.drawable.ic_file, "导入离线部署包", "选择本地 zip", () -> {
      agentDialogDismiss();
      pickPackage();
    });
    Ui.rowItem(group, R.drawable.ic_log, "运行日志", "普通事件与 Debug 诊断", () -> {
      agentDialogDismiss();
      openLogs();
    });
    agentDialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                      .setTitle(agentName(id))
                      .setView(box)
                      .setNegativeButton("关闭", null)
                      .create();
    agentDialog.show();
  }

  private AlertDialog agentDialog;

  private void agentVersions(String id) {
    if (!manager.complete()) { toast("请先完成运行环境初始化"); return; }
    if (busy()) { toast("已有环境操作正在进行，请等待完成后管理版本"); return; }
    LinearLayout content = NativeUi.column(this);
    content.setPadding(dp(18), 0, dp(18), dp(12));
    content.addView(NativeUi.body(this, "正在读取已安装版本…"));
    ScrollView scroll = new ScrollView(this); scroll.addView(content);
    AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle(agentName(id) + " 版本管理").setView(scroll).setNegativeButton("关闭", null).create();
    dialog.show();
    updateReader.execute(() -> {
      try {
        JSONObject result = manager.agentVersions(id);
        runOnUiThread(() -> {
          if (isDestroyed() || !dialog.isShowing()) return;
          content.removeAllViews();
          content.addView(NativeUi.body(this, "当前使用 " + result.optString("currentVersion")
              + "\n更新后保留旧版本。切换前验证启动程序；切换或删除不影响项目、会话和 API 配置。"));
          org.json.JSONArray versions = result.optJSONArray("data");
          if (versions == null || versions.length() == 0) {
            content.addView(NativeUi.body(this, "尚无可管理的版本，请先安装 Agent。")); return;
          }
          for (int i = 0; i < versions.length(); i++) {
            JSONObject entry = versions.optJSONObject(i);
            String release = entry.optString("id"), version = entry.optString("version");
            boolean current = entry.optBoolean("current"), available = entry.optBoolean("available");
            LinearLayout card = Ui.group(content);
            Ui.pad(card, 14, 12, 14, 12);
            card.addView(NativeUi.text(this, version + (current ? " · 当前使用" : " · 已保留"), 17, NativeUi.TEXT));
            TextView detail = NativeUi.text(this, "安装时间：" + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
                java.text.DateFormat.SHORT).format(new java.util.Date(entry.optLong("installedAt"))) + "\n" + release, 11, NativeUi.MUTED);
            card.addView(detail);
            if (!available) card.addView(NativeUi.body(this, "启动程序缺失；可删除此版本后重新安装。"));
            if (!current && available) card.addView(NativeUi.primaryButton(this, "切换到 " + version, () -> {
              dialog.dismiss(); versionOperation("agent-select", id, release);
            }));
            if (!current) card.addView(NativeUi.textButton(this, "删除此旧版本", () -> {
              new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("删除 " + version + "？")
                  .setMessage("仅删除保留的 Agent 程序版本。当前使用的版本、项目、会话和配置保持不变。删除后需要重新下载才能使用此版本。")
                  .setNegativeButton("取消", null).setPositiveButton("删除", (d, w) -> {
                    dialog.dismiss(); versionOperation("agent-remove", id, release);
                  }).show();
            }));
          }
          scroll.post(() -> {
            int max = (int) (getResources().getDisplayMetrics().heightPixels * .62f);
            if (scroll.getHeight() > max) { android.view.ViewGroup.LayoutParams size = scroll.getLayoutParams(); size.height = max; scroll.setLayoutParams(size); }
          });
        });
      } catch (Exception error) {
        AppLogs.failure(this, "runtime", "", "读取 Agent 版本失败", error);
        runOnUiThread(() -> {
          if (!isDestroyed() && dialog.isShowing()) {
            content.removeAllViews(); content.addView(NativeUi.body(this, "读取失败：" + NativeEngine.message(error) + "\n详情请查看运行日志。"));
            content.addView(NativeUi.textButton(this, "查看运行日志", () -> { dialog.dismiss(); logChannel = "debug"; logSource = "runtime"; logOperation = ""; resetLogSnapshot(); show("logs"); }));
          }
        });
      }
    });
  }

  private void versionOperation(String operation, String id, String release) {
    if (busy()) { toast("已有环境操作正在进行，请等待完成"); return; }
    try {
      launchOperation(new Intent(this, RuntimeService.class).putExtra("operation", operation).putExtra("agent", id).putExtra("release", release));
    } catch (Exception error) { toast("无法启动版本操作：" + NativeEngine.message(error)); }
  }

  private void checkAgentUpdates(String only) {
    if (checkingAgents) { toast("正在检查 Agent 更新，请稍候"); return; }
    checkingAgents = true;
    AppLogs.info(this, "runtime", "开始检查 Agent 官方更新");
    LinearLayout content = NativeUi.column(this);
    content.setPadding(dp(20), 0, dp(20), dp(12));
    TextView status = NativeUi.body(this, "正在连接官方发布源…"); content.addView(status);
    ScrollView scroll = new ScrollView(this); scroll.addView(content);
    AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle("Agent 更新").setView(scroll).setNegativeButton("关闭", null).create();
    dialog.show();
    updateReader.execute(() -> {
      org.json.JSONArray results = new org.json.JSONArray();
      String[] agents = only == null ? new String[] {"codex", "claude", "deepseek"} : new String[] {only};
      for (String id : agents) {
        runOnUiThread(() -> {
          if (!isDestroyed() && dialog.isShowing()) status.setText("正在检查 " + agentName(id) + " 官方发布版本…");
        });
        try { results.put(UpdateSources.agent(id)); }
        catch (Exception error) {
          AppLogs.failure(this, "runtime", "", "检查 " + id + " 官方更新失败", error);
          results.put(NativeUi.json("agent", id, "error", Diagnostics.redact(this, NativeEngine.message(error))));
        }
      }
      runOnUiThread(() -> {
        checkingAgents = false;
        if (isDestroyed() || !dialog.isShowing()) return;
        content.removeAllViews();
        for (int i = 0; i < results.length(); i++) {
          JSONObject entry = results.optJSONObject(i);
          String id = entry.optString("agent"), current = manager.installed(id), next = entry.optString("version");
          content.addView(NativeUi.text(this, agentName(id), 18, NativeUi.TEXT));
          if (entry.has("error")) {
            content.addView(NativeUi.body(this, "检查失败：" + entry.optString("error") + "\n详情请查看运行日志。"));
            content.addView(NativeUi.textButton(this, "查看运行日志", () -> { dialog.dismiss(); logChannel = "debug"; logSource = "runtime"; logOperation = ""; resetLogSnapshot(); show("logs"); }));
            continue;
          }
          AppLogs.info(this, "runtime", "检查 " + id + " 完成 · 当前 " + current + " · 官方 " + next);
          boolean missing = !installed(id);
          boolean newer;
          try { newer = missing || ReleaseVersions.compare(next, current) > 0; }
          catch (IllegalArgumentException error) {
            content.addView(NativeUi.body(this, "当前 " + current + " · 官方 " + next + "\n当前版本格式无法比较，请使用部署包管理。"));
            continue;
          }
          content.addView(NativeUi.body(this, "当前 " + current + "\n官方最新 " + next + (newer ? "" : "\n当前已是最新或更高版本")));
          if (newer) content.addView(NativeUi.primaryButton(this, missing ? "安装 " + next : "更新至 " + next, () -> {
            if (busy()) { toast("已有环境操作正在进行，请等待完成"); return; }
            if (!manager.complete()) { toast("请先在设置 → 运行环境完成初始化"); return; }
            dialog.dismiss();
            Intent update = new Intent(this, RuntimeService.class).putExtra("operation", "agent-update")
                .putExtra("agent", id).putExtra("version", next);
            try { launchOperation(update); }
            catch (Exception error) { toast("无法启动更新：" + NativeEngine.message(error)); }
          }));
        }
      });
    });
  }

  private void agentDialogDismiss() {
    if (agentDialog != null) {
      agentDialog.dismiss();
      agentDialog = null;
    }
  }

  private void pickPackage() {
    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    pick.addCategory(Intent.CATEGORY_OPENABLE);
    pick.setType("application/zip");
    for (String pkg : new String[] {"com.google.android.documentsui", "com.android.documentsui"}) {
      Intent candidate = new Intent(pick).setPackage(pkg);
      if (candidate.resolveActivity(getPackageManager()) != null) {
        pick = candidate;
        break;
      }
    }
    try {
      startActivityForResult(pick, 71);
    } catch (Exception e) {
      toast("未找到文件选择器");
    }
  }

  private String versionName() {
    try {
      return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (Exception ignored) {
      return "";
    }
  }

  private void statusView() {
    summary = NativeUi.statusCard(this, body, "读取运行状态…");
    summary.setTextColor(Ui.text);
    summary.setTextSize(13);
    summary.setLineSpacing(Ui.dp(this, 4), 1f);
  }
  private String agentName(String id) {
    return id.equals("codex") ? "Codex" : id.equals("claude") ? "Claude Code" : "DeepSeek";
  }
  private void github(String id) {
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(20), 0, dp(20), 0);
    EditText url = NativeUi.field(this, "GitHub Releases 部署包 HTTPS 链接", "", false);
    url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    NativeUi.addField(this, form, url);
    EditText sha = NativeUi.field(this, "发布者提供的 SHA-256", "", false);
    NativeUi.addField(this, form, sha);
    AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                             .setTitle("安装 " + agentName(id))
                             .setView(form)
                             .setNegativeButton("取消", null)
                             .setPositiveButton("下载并安装", null)
                             .create();
    dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w -> {
      String hash = sha.getText().toString().trim(), address = url.getText().toString().trim();
      if (!hash.matches("[0-9a-fA-F]{64}")) {
        sha.setError("需要 64 位 SHA-256");
        return;
      }
      if (!address.startsWith("https://github.com/")) {
        url.setError("请使用 GitHub Releases HTTPS 链接");
        return;
      }
      launchOperation(new Intent(this, RuntimeService.class)
              .putExtra("operation", "import")
              .putExtra("agent", id)
              .putExtra("url", address)
              .putExtra("sha256", hash));
      dialog.dismiss();
    }));
    dialog.show();
  }
  private void operate(String operation, String agent) {
    Intent intent = new Intent(this, RuntimeService.class).putExtra("operation", operation);
    if (agent != null)
      intent.putExtra("agent", agent);
    try {
      launchOperation(intent);
    } catch (Exception e) {
      toast("无法启动任务：" + e.getMessage());
    }
  }
  private void openLogs() {
    logChannel = "normal"; logSource = "all"; logOperation = "";
    resetLogSnapshot(); show("logs");
  }
  private void launchOperation(Intent intent) {
    if (busy() || pendingOperation != null) { toast("已有操作正在进行，请等待完成"); return; }
    String id = java.util.UUID.randomUUID().toString().substring(0, 8);
    intent.putExtra("origin", page).putExtra("operationId", id);
    try {
      pendingOperation = OperationState.start(id, intent.getStringExtra("operation"), intent.getStringExtra("agent"),
          page, "正在启动操作…", System.currentTimeMillis());
      showOperation(pendingOperation);
      startForegroundService(intent);
    } catch (Exception error) {
      AppLogs.failure(this, "runtime", id, "无法启动操作", error);
      try { pendingOperation = OperationState.finish(pendingOperation, "failed", "无法启动操作：" + NativeEngine.message(error), System.currentTimeMillis()); }
      catch (Exception ignored) {}
      showOperation(pendingOperation);
      try { RuntimeManager.write(new File(getFilesDir(), "runtime-operation.json"), pendingOperation.toString()); }
      catch (Exception ignored) {}
      pendingOperation = null;
    }
  }
  private void createOperationCard(LinearLayout root) {
    operationCard = Ui.col(this);
    operationCard.setBackground(Ui.round(this, Ui.cardHigh, 16, 1, Ui.lineSoft));
    Ui.pad(operationCard, 14, 10, 14, 8);
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
    space.setMargins(dp(16), dp(4), dp(16), dp(8));
    root.addView(operationCard, space);
    operationTitle = NativeUi.text(this, "", 14, NativeUi.TEXT); operationCard.addView(operationTitle);
    operationMessage = NativeUi.text(this, "", 12, NativeUi.MUTED);
    operationMessage.setMaxLines(5); operationMessage.setTextIsSelectable(true); operationCard.addView(operationMessage);
    operationProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    operationProgress.setIndeterminate(true);
    operationCard.addView(operationProgress, new LinearLayout.LayoutParams(-1, dp(6)));
    operationLogs = NativeUi.textButton(this, "查看运行日志", () -> {}); operationCard.addView(operationLogs);
    operationCard.setVisibility(View.GONE);
  }
  private void showOperation(JSONObject data) {
    if (operationCard == null || data == null) return;
    String status = data.optString("status");
    boolean running = status.equals("running"), failed = status.equals("failed") || status.equals("interrupted");
    if (status.isEmpty() || (!running && !data.optString("origin").equals(page))) {
      operationCard.setVisibility(View.GONE); return;
    }
    operationCard.setVisibility(View.VISIBLE);
    long end = running ? System.currentTimeMillis() : data.optLong("finishedAt", data.optLong("updatedAt"));
    long seconds = Math.max(0, end - data.optLong("startedAt", end)) / 1000;
    operationTitle.setText((running ? "进行中" : failed ? "操作失败" : "已完成") + " · "
        + data.optString("title", "环境操作") + " · " + seconds + "s");
    String detail = data.optString("message");
    if (!running && !failed) {
      org.json.JSONArray steps = data.optJSONArray("steps");
      if (steps != null) for (int i = Math.max(0, steps.length() - 3); i < steps.length(); i++)
        detail += "\n" + steps.optJSONObject(i).optString("message");
    }
    operationMessage.setText(detail + (failed ? "\n详情请查看运行日志，可按操作编号定位原因。" : ""));
    operationProgress.setVisibility(running ? View.VISIBLE : View.GONE);
    operationLogs.setVisibility(failed ? View.VISIBLE : View.GONE);
    operationLogs.setOnClickListener(v -> {
      logOperation = data.optString("id"); logChannel = "debug"; logSource = "all"; resetLogSnapshot(); show("logs");
    });
  }
  private void resetLogSnapshot() {
    forceLogRead = true;
    pageGeneration++; logLength = -1; logModified = -1; rawLog = ""; displayedLog = "";
    logFollowing = true; logTouch = false;
  }
  private void show(String name) {
    if (!page.equals(name)) history.add(page);
    page = name;
    render();
  }
  private void back() {
    if (history.isEmpty()) { finish(); return; }
    page = history.remove(history.size() - 1);
    render();
  }
  private void toast(String value) {
    Toast.makeText(this, value, Toast.LENGTH_LONG).show();
  }
  private void update() {
    try {
      String state = "运行环境："
          + (manager.complete()           ? "已初始化"
                  : manager.initialized() ? "基础系统已就绪，待补装组件"
                                          : "未初始化");
      File status = new File(getFilesDir(), "runtime-operation.json");
      if (status.isFile()) {
        JSONObject data = RuntimeManager.json(status);
        if (pendingOperation != null && data.optString("id").equals(pendingOperation.optString("id"))) pendingOperation = null;
        showOperation(pendingOperation == null ? data : pendingOperation);
      }
      if (pendingOperation != null) showOperation(pendingOperation);
      if (summary != null && !state.contentEquals(summary.getText()))
        summary.setText(state);
      for (java.util.Map.Entry<String, TextView> entry : agentLabels.entrySet()) {
        String id = entry.getKey(), label = installed(id) ? manager.installed(id) + " · 点击管理" : "未安装 · 点击安装";
        if (!label.contentEquals(entry.getValue().getText())) entry.getValue().setText(label);
      }
      readLogs();
    } catch (Exception ignored) {
    }
  }
  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 72 && result == RESULT_OK && data != null && data.getData() != null) {
      final android.net.Uri uri = data.getData(); exporting = true;
      logReader.execute(() -> {
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
          if (out == null) throw new IOException("无法写入报告");
          out.write(AppLogs.report(this).getBytes(java.nio.charset.StandardCharsets.UTF_8));
          runOnUiThread(() -> { if (!isDestroyed()) toast("诊断报告已导出，包含普通与 Debug 日志"); });
        } catch (Exception error) {
          AppLogs.failure(this, "runtime", "", "导出诊断报告失败", error);
          runOnUiThread(() -> { if (!isDestroyed()) toast("导出失败：" + NativeEngine.message(error)); });
        } finally { exporting = false; }
      });
    }
    if (request == 71 && result == RESULT_OK && data != null && data.getData() != null && selectedAgent != null) {
      try {
        getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (Exception ignored) {
      }
      launchOperation(new Intent(this, RuntimeService.class)
              .putExtra("operation", "import")
              .putExtra("agent", selectedAgent)
              .putExtra("uri", data.getData().toString()));
    }
  }
  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("page", page);
    state.putString("agent", selectedAgent);
    state.putString("logChannel", logChannel); state.putString("logSource", logSource);
    state.putString("logOperation", logOperation); state.putString("logFilter", logFilter); state.putString("logQuery", logQuery);
    state.putStringArrayList("history", history);
  }
  @Override
  protected void onResume() {
    super.onResume();
    if (page.equals("device"))
      render();
    handler.removeCallbacks(refresh);
    handler.post(refresh);
  }
  @Override
  protected void onDestroy() {
    handler.removeCallbacks(refresh);
    logReader.shutdown();
    updateReader.shutdown();
    super.onDestroy();
  }
  @Override
  protected void onPause() {
    handler.removeCallbacks(refresh);
    super.onPause();
  }
}
