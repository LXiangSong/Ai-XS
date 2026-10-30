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
  private TextView operationLogs, operationDismiss;
  private Button allAgentsStart, allAgentsStop;
  private JSONObject pendingOperation;
  private String logChannel = "normal", logSource = "all", logOperation = "";
  private final java.util.Map<String, TextView> agentLabels = new java.util.HashMap<>();
  private final java.util.Map<String, TextView> agentBadges = new java.util.HashMap<>();
  private boolean exporting;
  private TextView summary, logs, logCount;
  private Button latestLogs;
  private final java.util.HashMap<String, LinearLayout> logChips = new java.util.HashMap<>();
  private ScrollView logScroll;
  private String rawLog = "";
  private boolean forceLogRead = true, logAuto = true, logFollowing = true, logTouch = false, logReading = false;
  private long logLength = -1, logModified = -1;
  private int pageGeneration;
  private String dismissedOperationId = "";
  private String displayedLog = "";
  private final ExecutorService logReader = Executors.newSingleThreadExecutor();
  private final ExecutorService updateReader = Executors.newSingleThreadExecutor();
  private boolean readingAgentStatus;
  private long agentStatusAt;
  private final java.util.Map<String, JSONObject> agentStates = new java.util.HashMap<>();
  private final java.util.Map<String, Button> agentStartButtons = new java.util.HashMap<>(), agentStopButtons = new java.util.HashMap<>();
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
      dismissedOperationId = state.getString("dismissedOperationId", "");
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
    agentBadges.clear();
    allAgentsStart = null; allAgentsStop = null;
    agentStartButtons.clear(); agentStopButtons.clear();
    logs = null;
    logScroll = null;
    logCount = null;
    latestLogs = null;
    logChips.clear();
    pageGeneration++;
    LinearLayout root = NativeUi.page(this, pageTitle(), this::back);
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
    else if (page.equals("versions"))
      versionsPage();
    else if (page.equals("updates"))
      updatesPage();
    else if (page.equals("interfaces"))
      interfacesPage();
    else if (page.equals("device"))
      devicePage();
    else
      logsPage();
    update();
  }

  private void settingsPage() {
    statusView();

    NativeUi.section(this, body, "服务");
    NativeUi.tile(this, body, R.drawable.ic_key, "API 服务配置", "官方 API / 第三方 API，三个 Agent 独立配置",
        () -> startActivity(new Intent(this, NativeToolsActivity.class).putExtra("page", "api")));
    NativeUi.tile(this, body, R.drawable.ic_cloud, "运行环境",
        manager.complete() ? "已就绪 · 可检查与修复" : manager.initialized() ? "基础系统已就绪 · 待补装组件" : "未初始化",
        () -> show("environment"));
    NativeUi.tile(this, body, R.drawable.ic_spark, "Agent 管理", installedCount() + " / 3 已安装 · 启停、更新与版本管理",
        () -> show("agents"));

    NativeUi.section(this, body, "能力");
    NativeUi.tile(this, body, R.drawable.ic_device, "手机与开发工具",
        PhoneAccessibilityService.active != null ? "无障碍已开启 · 页面调试可配置" : "无障碍未开启 · 页面调试可配置",
        () -> show("device"));
    NativeUi.tile(this, body, R.drawable.ic_tools, "工具与扩展", "MCP 连接、GitHub 扩展与本地导入",
        () -> startActivity(new Intent(this, NativeToolsActivity.class).putExtra("engine", "codex")));
    NativeUi.tile(this, body, R.drawable.ic_log, "运行日志", "普通事件、Debug 诊断与报告导出", this::openLogs);

    Ui.gap(body, 4);
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
    statusView();

    boolean ready = manager.complete();
    NativeUi.section(this, body, "环境状态");
    LinearLayout states = Ui.group(body);
    infoRow(states, R.drawable.ic_cloud, "Linux 基础系统",
        ready ? "已初始化 · ARM64" : manager.initialized() ? "已就绪 · 待补装组件" : "未初始化");
    infoRow(states, R.drawable.ic_terminal, "共享组件", ready ? "Python · Node · Git 已就绪" : "等待安装");
    Ui.rowItem(states, R.drawable.ic_spark, "Agent 运行时", installedCount() + " / 3 已安装", () -> show("agents"));

    NativeUi.section(this, body, "维护");
    LinearLayout actions = Ui.row(this);
    Button repair = NativeUi.primaryButton(this, ready ? "修复 / 补装" : "初始化环境", () -> operate("initialize", null));
    Button check = NativeUi.outlinedButton(this, "检查环境", () -> operate("check", null));
    actions.addView(repair, new LinearLayout.LayoutParams(0, -2, 1f));
    LinearLayout.LayoutParams checkSpace = new LinearLayout.LayoutParams(0, -2, 1f);
    checkSpace.leftMargin = dp(8);
    actions.addView(check, checkSpace);
    body.addView(actions);
    NativeUi.tile(this, body, R.drawable.ic_log, "运行日志", "普通事件与 Debug 诊断", this::openLogs);
  }

  private void agentsPage() {
    statusView();

    LinearLayout controls = Ui.row(this);
    allAgentsStart = NativeUi.primaryButton(this, "全部启动", () -> controlAgent("start", "all"));
    allAgentsStop = NativeUi.outlinedButton(this, "全部停止", () -> controlAgent("stop", "all"));
    controls.addView(allAgentsStart, new LinearLayout.LayoutParams(0, -2, 1f));
    LinearLayout.LayoutParams stopSpace = new LinearLayout.LayoutParams(0, -2, 1f);
    stopSpace.leftMargin = dp(8);
    controls.addView(allAgentsStop, stopSpace);
    body.addView(controls);

    NativeUi.section(this, body, "Agent");
    for (String id : new String[] {"codex", "claude", "deepseek"}) {
      LinearLayout card = Ui.group(body);
      Ui.pad(card, 14, 12, 14, 2);
      LinearLayout header = Ui.row(this);
      header.setGravity(android.view.Gravity.CENTER_VERTICAL);
      LinearLayout labels = Ui.col(this);
      header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
      labels.addView(Ui.text(this, agentName(id), 16, Ui.text, Ui.medium(this)));
      TextView label = Ui.text(this, agentStateText(id), 12.5f, Ui.muted, null);
      LinearLayout.LayoutParams labelSpace = new LinearLayout.LayoutParams(-1, -2);
      labelSpace.topMargin = dp(3);
      labels.addView(label, labelSpace);
      agentLabels.put(id, label);
      TextView badge = Ui.text(this, installed(id) ? "已安装" : "未安装", 11,
          installed(id) ? Ui.onAccentSoft : Ui.muted, Ui.medium(this));
      badge.setPadding(dp(9), dp(4), dp(9), dp(4));
      badge.setBackground(Ui.round(installed(id) ? Ui.accentSoft : Ui.cardHigh, 999));
      LinearLayout.LayoutParams badgeSpace = new LinearLayout.LayoutParams(-2, -2);
      badgeSpace.leftMargin = dp(8);
      header.addView(badge, badgeSpace);
      agentBadges.put(id, badge);
      card.addView(header);

      LinearLayout actions = Ui.row(this);
      actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
      Button start = NativeUi.textButton(this, "启动", () -> controlAgent("start", id));
      start.setTextColor(Ui.accent);
      start.setContentDescription("启动 " + agentName(id));
      agentStartButtons.put(id, start);
      Button stop = NativeUi.textButton(this, "停止", () -> controlAgent("stop", id));
      stop.setContentDescription("停止 " + agentName(id));
      agentStopButtons.put(id, stop);
      Button manage = NativeUi.textButton(this, "版本与安装", () -> agentSheet(id));
      manage.setTextColor(Ui.accent);
      manage.setContentDescription("管理 " + agentName(id));
      actions.addView(start, new LinearLayout.LayoutParams(0, -2, 1f));
      actions.addView(stop, new LinearLayout.LayoutParams(0, -2, 1f));
      actions.addView(manage, new LinearLayout.LayoutParams(0, -2, 1.4f));
      card.addView(actions);
    }

    NativeUi.section(this, body, "维护");
    LinearLayout maintenance = Ui.group(body);
    Ui.rowItem(maintenance, R.drawable.ic_cloud, "检查全部 Agent 更新", "读取官方发布源，逐个查看新版本",
        () -> show("updates"));
    Ui.rowItem(maintenance, R.drawable.ic_check, "检查 Agent 接口", "版本、模型与工具连接",
        () -> show("interfaces"));
    Ui.rowItem(maintenance, R.drawable.ic_log, "运行日志", "普通事件与 Debug 诊断", this::openLogs);
    agentStatusAt = 0;
  }
  private String agentStateText(String id) {
    if (!installed(id)) return "未安装 · 请先安装";
    JSONObject state = agentStates.get(id);
    String status;
    if (!NativeEngine.ready()) {
      boolean enabled = true;
      try { enabled = RuntimeManager.json(new File(manager.root, "opt/aixs/native/.state/engine-services.json")).optBoolean(id, true); }
      catch (Exception ignored) {}
      status = enabled ? "服务未启动" : "已停止";
    }
    else if (state == null) status = "正在读取状态…";
    else if (!state.optBoolean("enabled", true)) status = "已停止";
    else if (state.optString("state").equals("starting")) status = "正在启动";
    else if (state.optString("state").equals("stopping")) status = "正在停止";
    else if (state.optString("state").equals("error")) status = "异常 · " + state.optString("error");
    else if (state.optInt("activeTasks") > 0) status = "运行中 · " + state.optInt("activeTasks") + " 个任务";
    else status = "已启动 · 空闲就绪";
    return manager.installed(id) + " · " + status;
  }
  private void readAgentStatus() {
    if (!page.equals("agents") || !NativeEngine.ready() || readingAgentStatus || System.currentTimeMillis() - agentStatusAt < 1500) return;
    readingAgentStatus = true; agentStatusAt = System.currentTimeMillis();
    NativeEngine.call("native/agents/status", new JSONObject(), result -> {
      readingAgentStatus = false;
      org.json.JSONArray states = result.optJSONArray("data");
      if (states != null) for (int i = 0; i < states.length(); i++) {
        JSONObject state = states.optJSONObject(i); if (state != null) agentStates.put(state.optString("id"), state);
      }
      if (!isDestroyed()) updateAgentRows();
    }, error -> { readingAgentStatus = false; });
  }
  private void updateAgentRows() {
    boolean operating = busy() || pendingOperation != null;
    if (allAgentsStart != null) allAgentsStart.setEnabled(!operating && installedCount() > 0);
    if (allAgentsStop != null) allAgentsStop.setEnabled(!operating && NativeEngine.ready());
    for (java.util.Map.Entry<String, TextView> entry : agentLabels.entrySet()) {
      String id = entry.getKey(), label = agentStateText(id);
      if (!label.contentEquals(entry.getValue().getText())) entry.getValue().setText(label);
      agentStartButtons.get(id).setEnabled(!operating && installed(id));
      agentStopButtons.get(id).setEnabled(!operating && installed(id) && NativeEngine.ready());
      TextView badge = agentBadges.get(id);
      if (badge != null) {
        boolean present = installed(id);
        badge.setText(present ? "已安装" : "未安装");
        badge.setTextColor(present ? Ui.onAccentSoft : Ui.muted);
        badge.setBackground(Ui.round(present ? Ui.accentSoft : Ui.cardHigh, 999));
      }
    }
  }
  private void controlAgent(String action, String target) {
    if (busy() || pendingOperation != null) { toast("已有操作正在进行，请等待完成"); return; }
    if (action.equals("start") && !manager.complete()) { toast("请先在设置 → 运行环境中初始化"); return; }
    if (action.equals("stop")) {
      String name = target.equals("all") ? "全部 Agent" : agentName(target);
      new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("停止" + name + "？")
          .setMessage("将中断" + (target.equals("all") ? "所有" : name + " 的") + "运行中任务。项目、会话和 API 配置将保留。")
          .setNegativeButton("取消", null).setPositiveButton("停止", (d, w) -> launchAgentControl(action, target)).show();
    } else launchAgentControl(action, target);
  }
  private void launchAgentControl(String action, String target) {
    launchOperation(new Intent(this, RuntimeService.class).putExtra("operation", "agents-" + action)
        .putExtra("agent", target).putExtra("confirmed", action.equals("stop")));
    agentStatusAt = 0;
    updateAgentRows();
  }

  private void devicePage() {
    boolean accessibility = PhoneAccessibilityService.active != null;
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
    String[][] channels = {{"normal", "普通"}, {"debug", "调试"}, {"legacy", "历史"}};
    for (int i = 0; i < channels.length; i++) {
      final String channelId = channels[i][0];
      boolean active = logChannel.equals(channelId);
      TextView tab = Ui.text(this, channels[i][1], 12.5f, active ? Ui.onAccentSoft : Ui.muted,
          active ? Ui.medium(this) : null);
      tab.setGravity(android.view.Gravity.CENTER);
      tab.setBackground(Ui.round(this, active ? Ui.accentSoft : Ui.cardHigh, 999,
          active ? 0f : 0.8f, active ? Ui.accentSoft : Ui.lineSoft));
      tab.setOnClickListener(v -> changeLogChannel(channelId));
      Ui.touchable(tab);
      LinearLayout.LayoutParams tabSpace = new LinearLayout.LayoutParams(0, Ui.dp(this, 36), 1f);
      if (i > 0) tabSpace.leftMargin = Ui.dp(this, 8);
      modes.addView(tab, tabSpace);
    }
    body.addView(modes);

    TextView hint = NativeUi.text(this, logChannel.equals("debug") ? "底层命令输出、错误堆栈与浏览器诊断 · 自动脱敏"
        : logChannel.equals("normal") ? "操作阶段、完成结果与错误提醒" : "升级前保留的旧版输出", 12, NativeUi.MUTED);
    LinearLayout.LayoutParams hintSpace = new LinearLayout.LayoutParams(-1, -2);
    hintSpace.leftMargin = Ui.dp(this, 2);
    hintSpace.topMargin = Ui.dp(this, 8);
    body.addView(hint, hintSpace);

    if (!logOperation.isEmpty()) {
      LinearLayout scope = Ui.group(body);
      LinearLayout scopeRow = Ui.row(this);
      scopeRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
      Ui.pad(scopeRow, 14, 6, 10, 6);
      scopeRow.addView(Ui.text(this, "仅显示操作 #" + logOperation, 12.5f, Ui.text, Ui.medium(this)),
          new LinearLayout.LayoutParams(0, -2, 1f));
      TextView all = Ui.text(this, "查看全部", 12.5f, Ui.accent, Ui.medium(this));
      Ui.pad(all, 8, 6, 8, 6);
      Ui.touchable(all);
      Ui.rippleRounded(all, 8, Ui.accent);
      all.setOnClickListener(v -> { logOperation = ""; render(); });
      scopeRow.addView(all);
      scope.addView(scopeRow);
    }

    LinearLayout toolbar = Ui.group(body);
    boolean legacy = logChannel.equals("legacy");

    HorizontalScrollView sourceScroll = new HorizontalScrollView(this);
    sourceScroll.setHorizontalScrollBarEnabled(false);
    sourceScroll.setClipToPadding(false);
    sourceScroll.setHorizontalFadingEdgeEnabled(true);
    sourceScroll.setFadingEdgeLength(Ui.dp(this, 24));
    LinearLayout sourceRow = Ui.row(this);
    Ui.pad(sourceRow, 12, 10, 12, 4);
    String[][] sources = {{"all", "全部来源"}, {"runtime", "运行环境"}, {"backend", "Agent 服务"},
        {"app-update", "应用更新"}, {"browser", "浏览器"}, {"app", "应用崩溃"}};
    for (String[] source : sources) {
      boolean active = logSource.equals(source[0]);
      TextView chip = Ui.text(this, source[1], 12.5f, active ? Ui.onAccentSoft : Ui.muted,
          active ? Ui.medium(this) : null);
      chip.setGravity(android.view.Gravity.CENTER);
      chip.setBackground(Ui.round(this, active ? Ui.accentSoft : Ui.cardHigh, 999, 0.8f, Ui.lineSoft));
      chip.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
      chip.setMinHeight(Ui.dp(this, 30));
      chip.setAlpha(legacy ? 0.45f : 1f);
      if (!legacy) {
        chip.setOnClickListener(v -> {
          if (!logSource.equals(source[0])) { logSource = source[0]; render(); }
        });
        Ui.touchable(chip);
        Ui.rippleRounded(chip, 999, Ui.accent);
      }
      LinearLayout.LayoutParams chipSpace = new LinearLayout.LayoutParams(-2, -2);
      chipSpace.rightMargin = Ui.dp(this, 6);
      sourceRow.addView(chip, chipSpace);
    }
    sourceScroll.addView(sourceRow);
    toolbar.addView(sourceScroll);

    LinearLayout filters = Ui.row(this);
    Ui.pad(filters, 12, 6, 12, 2);
    filters.addView(logChip("全部", "all"));
    filters.addView(logChip("警告", "warn"));
    filters.addView(logChip("错误", "error"));
    toolbar.addView(filters);

    EditText search = new EditText(this);
    search.setSingleLine(true);
    search.setHint("搜索日志内容");
    search.setTextSize(13);
    search.setTextColor(Ui.text);
    search.setHintTextColor(Ui.faint);
    search.setText(logQuery);
    search.setBackground(Ui.round(this, Ui.cardHigh, 10, 0.8f, Ui.lineSoft));
    search.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
    search.setMinHeight(0);
    LinearLayout.LayoutParams searchSpace = new LinearLayout.LayoutParams(-1, -2);
    searchSpace.leftMargin = Ui.dp(this, 12);
    searchSpace.rightMargin = Ui.dp(this, 12);
    searchSpace.topMargin = Ui.dp(this, 6);
    toolbar.addView(search, searchSpace);
    search.addTextChangedListener(new android.text.TextWatcher() {
      public void afterTextChanged(android.text.Editable value) {
        logQuery = value.toString();
        renderLogs();
      }
      public void beforeTextChanged(CharSequence v, int a, int b, int c) {}
      public void onTextChanged(CharSequence v, int a, int b, int c) {}
    });

    LinearLayout followRow = Ui.row(this);
    followRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
    Ui.pad(followRow, 12, 2, 10, 2);
    followRow.addView(Ui.text(this, "自动刷新", 13, Ui.text, Ui.medium(this)),
        new LinearLayout.LayoutParams(0, -2, 1f));
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
    boxSpace.topMargin = Ui.dp(this, 10);
    body.addView(box, boxSpace);
    logs.setBackground(null);

    LinearLayout.LayoutParams countSpace = new LinearLayout.LayoutParams(-1, -2);
    countSpace.leftMargin = Ui.dp(this, 2);
    countSpace.topMargin = Ui.dp(this, 7);
    logCount = Ui.text(this, "", 11.5f, Ui.faint, null);
    body.addView(logCount, countSpace);

    LinearLayout actions = Ui.row(this);
    actions.addView(NativeUi.textButton(this, "复制", this::copyLogs), new LinearLayout.LayoutParams(0, -2, 1f));
    latestLogs = NativeUi.textButton(this, "跳到最新", () -> {
      logFollowing = true;
      renderLogs();
      scrollLogsToEnd();
    });
    actions.addView(latestLogs, new LinearLayout.LayoutParams(0, -2, 1f));
    actions.addView(NativeUi.textButton(this, "导出", this::exportLogs), new LinearLayout.LayoutParams(0, -2, 1f));
    body.addView(actions);
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
      showVersions(id);
    });
    Ui.rowItem(group, R.drawable.ic_check, "检查官方更新", installed(id) ? "当前 " + manager.installed(id) : "检查最新可安装版本", () -> {
      agentDialogDismiss();
      show("updates");
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

  private void showVersions(String id) {
    if (!manager.complete()) { toast("请先完成运行环境初始化"); return; }
    selectedAgent = id;
    show("versions");
  }

  private void versionsPage() {
    final String id = selectedAgent == null ? "codex" : selectedAgent;
    final String current = manager.installed(id);
    if (!installed(id)) {
      NativeUi.statusCard(this, body, agentName(id) + " 尚未安装\n安装完成后，可在这里切换版本或回退旧版本。");
      NativeUi.section(this, body, "安装");
      LinearLayout options = Ui.group(body);
      Ui.rowItem(options, R.drawable.ic_cloud, "从官方来源安装", "固定版本发布包", () -> operate("agent", id));
      Ui.rowItem(options, R.drawable.ic_code, "从 GitHub Releases 安装", "需要部署包链接与 SHA-256", () -> github(id));
      Ui.rowItem(options, R.drawable.ic_file, "导入离线部署包", "选择本地 zip", this::pickPackage);
      return;
    }

    LinearLayout hero = Ui.group(body);
    LinearLayout heroRow = Ui.row(this);
    heroRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
    Ui.pad(heroRow, 14, 12, 14, 12);
    LinearLayout heroLabels = Ui.col(this);
    heroRow.addView(heroLabels, new LinearLayout.LayoutParams(0, -2, 1f));
    heroLabels.addView(Ui.text(this, "当前使用", 11.5f, Ui.muted, null));
    TextView heroVersion = Ui.text(this, current, 21, Ui.text, Ui.medium(this));
    LinearLayout.LayoutParams heroSpace = new LinearLayout.LayoutParams(-1, -2);
    heroSpace.topMargin = dp(2);
    heroLabels.addView(heroVersion, heroSpace);
    TextView badge = Ui.text(this, "已安装", 11, Ui.onAccentSoft, Ui.medium(this));
    badge.setPadding(dp(9), dp(4), dp(9), dp(4));
    badge.setBackground(Ui.round(Ui.accentSoft, 999));
    heroRow.addView(badge);
    hero.addView(heroRow);

    TextView note = Ui.text(this,
        "切换前会校验启动程序。旧版本可保留用于回退；切换或删除不影响项目、会话和 API 配置。",
        12, Ui.muted, null);
    note.setLineSpacing(dp(3), 1f);
    LinearLayout.LayoutParams noteSpace = new LinearLayout.LayoutParams(-1, -2);
    noteSpace.leftMargin = dp(2);
    noteSpace.topMargin = dp(2);
    noteSpace.bottomMargin = dp(14);
    body.addView(note, noteSpace);

    NativeUi.section(this, body, "已安装版本");
    final LinearLayout list = Ui.col(this);
    body.addView(list);
    TextView loading = Ui.text(this, "正在读取已安装版本…", 12.5f, Ui.muted, null);
    loading.setPadding(dp(2), dp(6), dp(2), dp(6));
    list.addView(loading);

    final int generation = pageGeneration;
    updateReader.execute(() -> {
      try {
        JSONObject result = manager.agentVersions(id);
        runOnUiThread(() -> {
          if (isDestroyed() || generation != pageGeneration || !page.equals("versions")
              || !id.equals(selectedAgent)) return;
          list.removeAllViews();
          org.json.JSONArray versions = result.optJSONArray("data");
          if (versions == null || versions.length() == 0) {
            TextView empty = Ui.text(this, "尚无可管理的版本，请先安装 Agent。", 12.5f, Ui.muted, null);
            empty.setPadding(dp(2), dp(6), dp(2), dp(6));
            list.addView(empty);
            return;
          }
          for (int i = 0; i < versions.length(); i++) {
            JSONObject entry = versions.optJSONObject(i);
            if (entry == null) continue;
            String release = entry.optString("id"), version = entry.optString("version");
            boolean active = entry.optBoolean("current"), available = entry.optBoolean("available");
            LinearLayout card = Ui.group(list);
            Ui.pad(card, 14, 12, 14, 12);
            LinearLayout row = Ui.row(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout labels = Ui.col(this);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
            labels.addView(Ui.text(this, version, 15.5f, Ui.text, Ui.medium(this)));
            String time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,
                java.text.DateFormat.SHORT).format(new java.util.Date(entry.optLong("installedAt")));
            TextView meta = Ui.text(this, "安装于 " + time, 11.5f, Ui.faint, null);
            LinearLayout.LayoutParams metaSpace = new LinearLayout.LayoutParams(-1, -2);
            metaSpace.topMargin = dp(3);
            labels.addView(meta, metaSpace);
            TextView state = Ui.pill(this, active ? "当前" : available ? "已保留" : "不可用",
                active ? Ui.onAccentSoft : available ? Ui.muted : Ui.warn,
                active ? Ui.accentSoft : Ui.cardHigh);
            LinearLayout.LayoutParams stateSpace = new LinearLayout.LayoutParams(-2, -2);
            stateSpace.leftMargin = dp(10);
            row.addView(state, stateSpace);
            card.addView(row);

            if (!available) {
              TextView warning = Ui.text(this, "启动程序缺失；可删除此版本后重新安装。", 11.5f, Ui.warn, null);
              warning.setLineSpacing(dp(2), 1f);
              LinearLayout.LayoutParams warningSpace = new LinearLayout.LayoutParams(-1, -2);
              warningSpace.leftMargin = dp(2);
              warningSpace.topMargin = dp(7);
              card.addView(warning, warningSpace);
            }
            if (!active) {
              LinearLayout actions = Ui.row(this);
              actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
              Button use = NativeUi.textButton(this, "切换到此版本", () -> versionOperation("agent-select", id, release));
              use.setTextColor(Ui.accent);
              actions.addView(use, new LinearLayout.LayoutParams(0, -2, 1.3f));
              Button remove = NativeUi.textButton(this, "删除", () -> confirmRemoveVersion(id, release, version));
              remove.setTextColor(Ui.muted);
              actions.addView(remove, new LinearLayout.LayoutParams(0, -2, .7f));
              LinearLayout.LayoutParams actionsSpace = new LinearLayout.LayoutParams(-1, -2);
              actionsSpace.topMargin = dp(4);
              card.addView(actions, actionsSpace);
            }
          }

          LinearLayout more = Ui.group(list);
          Ui.rowItem(more, R.drawable.ic_cloud, "检查官方更新", "查看是否有可安装的新版本", () -> show("updates"));
          Ui.rowItem(more, R.drawable.ic_code, "从 GitHub Releases 安装", "需要部署包链接与 SHA-256", () -> github(id));
          Ui.rowItem(more, R.drawable.ic_file, "导入离线部署包", "选择本地 zip", this::pickPackage);
        });
      } catch (Exception error) {
        AppLogs.failure(this, "runtime", "", "读取 Agent 版本失败", error);
        runOnUiThread(() -> {
          if (isDestroyed() || generation != pageGeneration || !page.equals("versions")) return;
          list.removeAllViews();
          TextView failed = Ui.text(this, "读取失败：" + NativeEngine.message(error) + "\n详情请查看运行日志。",
              12.5f, Ui.danger, null);
          failed.setLineSpacing(dp(3), 1f);
          failed.setPadding(dp(2), dp(6), dp(2), dp(6));
          list.addView(failed);
          Button retry = NativeUi.textButton(this, "重试", this::render);
          retry.setTextColor(Ui.accent);
          list.addView(retry);
          Button logs = NativeUi.textButton(this, "查看运行日志", () -> {
            logChannel = "debug"; logSource = "runtime"; logOperation = "";
            resetLogSnapshot(); show("logs");
          });
          list.addView(logs);
        });
      }
    });
  }

  private void confirmRemoveVersion(String id, String release, String version) {
    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("删除 " + version + "？")
        .setMessage("仅删除保留的 Agent 程序版本。当前使用的版本、项目、会话和配置保持不变。删除后需要重新下载才能使用此版本。")
        .setNegativeButton("取消", null)
        .setPositiveButton("删除", (d, w) -> versionOperation("agent-remove", id, release))
        .show();
  }

  private void versionOperation(String operation, String id, String release) {
    if (busy()) { toast("已有环境操作正在进行，请等待完成"); return; }
    try {
      launchOperation(new Intent(this, RuntimeService.class).putExtra("operation", operation).putExtra("agent", id).putExtra("release", release));
    } catch (Exception error) { toast("无法启动版本操作：" + NativeEngine.message(error)); }
  }

  private void loadingCard(LinearLayout parent, String text) {
    LinearLayout box = Ui.group(parent);
    LinearLayout row = Ui.row(this);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    Ui.pad(row, 14, 14, 14, 14);
    row.addView(Ui.text(this, text, 12.5f, Ui.muted, null));
    box.addView(row);
  }

  private void introText(String text) {
    TextView intro = Ui.text(this, text, 12.5f, Ui.muted, null);
    intro.setLineSpacing(dp(3), 1f);
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
    space.leftMargin = dp(2);
    space.rightMargin = dp(2);
    space.bottomMargin = dp(8);
    body.addView(intro, space);
  }

  private void updatesPage() {
    final int generation = pageGeneration;
    introText("从官方发布源读取最新版本。检查不会安装或修改文件，确认后再更新。");
    NativeUi.section(this, body, "Agent");
    final LinearLayout list = Ui.col(this);
    body.addView(list);
    loadingCard(list, "正在读取官方发布版本…");

    updateReader.execute(() -> {
      final java.util.LinkedHashMap<String, JSONObject> found = new java.util.LinkedHashMap<>();
      final java.util.LinkedHashMap<String, String> errors = new java.util.LinkedHashMap<>();
      for (String id : new String[] {"codex", "claude", "deepseek"}) {
        try {
          found.put(id, UpdateSources.agent(id));
        } catch (Exception error) {
          errors.put(id, Diagnostics.redact(this, NativeEngine.message(error)));
          AppLogs.failure(this, "runtime", "", "检查 " + id + " 官方更新失败", error);
        }
      }
      runOnUiThread(() -> {
        if (isDestroyed() || generation != pageGeneration || !page.equals("updates")) return;
        list.removeAllViews();
        for (String id : new String[] {"codex", "claude", "deepseek"}) {
          String current = manager.installed(id);
          boolean missing = !installed(id);
          JSONObject entry = found.get(id);
          String error = errors.get(id);
          String next = entry == null ? "" : entry.optString("version");
          boolean newer = false;
          try {
            newer = error == null && !next.isEmpty() && (missing || ReleaseVersions.compare(next, current) > 0);
          } catch (IllegalArgumentException ignored) {}
          LinearLayout card = Ui.group(list);
          Ui.pad(card, 14, 12, 14, 12);
          LinearLayout head = Ui.row(this);
          head.setGravity(android.view.Gravity.CENTER_VERTICAL);
          head.addView(Ui.text(this, agentName(id), 16, Ui.text, Ui.medium(this)),
              new LinearLayout.LayoutParams(0, -2, 1f));
          TextView badge = Ui.pill(this, error != null ? "检查失败" : newer ? (missing ? "可安装" : "可更新") : "已是最新",
              error != null ? Ui.warn : newer ? Ui.onAccentSoft : Ui.muted,
              error == null && newer ? Ui.accentSoft : Ui.cardHigh);
          LinearLayout.LayoutParams badgeSpace = new LinearLayout.LayoutParams(-2, -2);
          badgeSpace.leftMargin = dp(10);
          head.addView(badge, badgeSpace);
          card.addView(head);
          TextView detail = error != null
              ? Ui.text(this, "检查失败：" + error, 12, Ui.warn, null)
              : Ui.text(this, (missing ? "未安装" : "当前 " + current) + (next.isEmpty() ? "" : " · 官方 " + next),
                  12.5f, Ui.muted, null);
          detail.setLineSpacing(dp(3), 1f);
          LinearLayout.LayoutParams detailSpace = new LinearLayout.LayoutParams(-1, -2);
          detailSpace.topMargin = dp(6);
          card.addView(detail, detailSpace);
          LinearLayout actions = Ui.row(this);
          actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
          if (newer) {
            Button update = NativeUi.textButton(this, (missing ? "安装 " : "更新至 ") + next, () -> {
              if (busy()) { toast("已有操作正在进行，请稍候"); return; }
              if (!manager.complete()) { toast("请先在运行环境中初始化"); return; }
              Intent intent = new Intent(this, RuntimeService.class)
                  .putExtra("operation", missing ? "agent" : "agent-update").putExtra("agent", id);
              if (!missing) intent.putExtra("version", next);
              try {
                launchOperation(intent);
              } catch (Exception failure) {
                toast("无法启动更新：" + NativeEngine.message(failure));
              }
            });
            update.setTextColor(Ui.accent);
            actions.addView(update, new LinearLayout.LayoutParams(0, -2, 1f));
          } else if (error != null) {
            Button retry = NativeUi.textButton(this, "重新检查", () -> show("updates"));
            retry.setTextColor(Ui.accent);
            actions.addView(retry, new LinearLayout.LayoutParams(0, -2, 1f));
          }
          if (actions.getChildCount() > 0) card.addView(actions);
        }
      });
    });

    NativeUi.section(this, body, "其他");
    LinearLayout more = Ui.group(body);
    Ui.rowItem(more, R.drawable.ic_cloud, "重新检查", "重新读取官方发布版本", () -> show("updates"));
    Ui.rowItem(more, R.drawable.ic_log, "运行日志", "普通事件与 Debug 诊断", this::openLogs);
  }

  private String agentVersionText(String version) {
    String value = version == null ? "" : version.trim();
    value = value.replaceFirst("^(codex-cli|claude-code)\\s+", "");
    value = value.replaceFirst("\\s*\\(Claude Code\\)$", "");
    return value;
  }

  private void interfacesPage() {
    introText("检查三个 Agent 的版本、模型服务与内置 MCP 工具连接。检查不会发送推理请求。");
    if (!NativeEngine.ready()) {
      LinearLayout card = Ui.group(body);
      LinearLayout box = Ui.col(this);
      Ui.pad(box, 14, 14, 14, 10);
      box.addView(Ui.text(this, "Agent 服务未启动", 15, Ui.text, Ui.medium(this)));
      TextView note = Ui.text(this, "接口检查需要 Agent 服务运行。启动服务后再打开此页。", 12, Ui.muted, null);
      note.setLineSpacing(dp(3), 1f);
      LinearLayout.LayoutParams noteSpace = new LinearLayout.LayoutParams(-1, -2);
      noteSpace.topMargin = dp(3);
      box.addView(note, noteSpace);
      Button start = NativeUi.primaryButton(this, "全部启动", () -> controlAgent("start", "all"));
      LinearLayout.LayoutParams startSpace = new LinearLayout.LayoutParams(-1, -2);
      startSpace.topMargin = dp(6);
      box.addView(start, startSpace);
      card.addView(box);
      NativeUi.section(this, body, "其他");
      LinearLayout more = Ui.group(body);
      Ui.rowItem(more, R.drawable.ic_cloud, "重新检查", "服务启动后再次检查", () -> render());
      Ui.rowItem(more, R.drawable.ic_log, "运行日志", "查看服务启动与检查输出", this::openLogs);
      return;
    }

    NativeUi.section(this, body, "检查结果");
    final LinearLayout list = Ui.col(this);
    body.addView(list);
    loadingCard(list, "正在检查接口…");
    final int generation = pageGeneration;
    NativeEngine.call("native/check", new JSONObject(), result -> {
      if (isDestroyed() || generation != pageGeneration || !page.equals("interfaces")) return;
      list.removeAllViews();
      org.json.JSONArray data = result.optJSONArray("data");
      if (data == null || data.length() == 0) {
        loadingCard(list, "没有可检查的 Agent，请先安装并启动。");
      } else {
        for (int i = 0; i < data.length(); i++) {
          JSONObject item = data.optJSONObject(i);
          if (item == null) continue;
          LinearLayout card = Ui.group(list);
          Ui.pad(card, 14, 12, 14, 12);
          LinearLayout head = Ui.row(this);
          head.setGravity(android.view.Gravity.CENTER_VERTICAL);
          head.addView(Ui.text(this, agentName(item.optString("agent")), 16, Ui.text, Ui.medium(this)),
              new LinearLayout.LayoutParams(0, -2, 1f));
          TextView badge = Ui.pill(this, "正常", Ui.onAccentSoft, Ui.accentSoft);
          LinearLayout.LayoutParams badgeSpace = new LinearLayout.LayoutParams(-2, -2);
          badgeSpace.leftMargin = dp(10);
          head.addView(badge, badgeSpace);
          card.addView(head);

          boolean key = item.optBoolean("keyConfigured");
          String mode = item.optString("apiMode").equals("official") ? "官方 API" : "第三方 API";
          TextView meta = Ui.text(this, "版本 " + agentVersionText(item.optString("version")) + " · " + mode
              + " · " + (key ? "密钥已配置" : "密钥未配置"), 12.5f, key ? Ui.muted : Ui.warn, null);
          meta.setLineSpacing(dp(3), 1f);
          LinearLayout.LayoutParams metaSpace = new LinearLayout.LayoutParams(-1, -2);
          metaSpace.topMargin = dp(4);
          card.addView(meta, metaSpace);

          LinearLayout chips = Ui.row(this);
          chips.setGravity(android.view.Gravity.CENTER_VERTICAL);
          TextView models = Ui.text(this, "模型 " + item.optInt("models"), 11.5f, Ui.muted, Ui.medium(this));
          models.setPadding(dp(9), dp(4), dp(9), dp(4));
          models.setBackground(Ui.round(Ui.cardHigh, 999));
          chips.addView(models);
          TextView tools = Ui.text(this, "MCP 工具 " + item.optInt("tools"), 11.5f, Ui.muted, Ui.medium(this));
          tools.setPadding(dp(9), dp(4), dp(9), dp(4));
          tools.setBackground(Ui.round(Ui.cardHigh, 999));
          LinearLayout.LayoutParams toolsSpace = new LinearLayout.LayoutParams(-2, -2);
          toolsSpace.leftMargin = dp(6);
          chips.addView(tools, toolsSpace);
          LinearLayout.LayoutParams chipsSpace = new LinearLayout.LayoutParams(-1, -2);
          chipsSpace.topMargin = dp(8);
          card.addView(chips, chipsSpace);
        }
      }
      String detail = result.optString("detail");
      if (!detail.isEmpty()) {
        TextView note = Ui.text(this, detail, 11.5f, Ui.faint, null);
        LinearLayout.LayoutParams noteSpace = new LinearLayout.LayoutParams(-1, -2);
        noteSpace.leftMargin = dp(4);
        noteSpace.topMargin = dp(8);
        noteSpace.bottomMargin = dp(10);
        list.addView(note, noteSpace);
      }
    }, error -> {
      if (isDestroyed() || generation != pageGeneration || !page.equals("interfaces")) return;
      list.removeAllViews();
      LinearLayout card = Ui.group(list);
      LinearLayout box = Ui.col(this);
      Ui.pad(box, 14, 14, 14, 10);
      box.addView(Ui.text(this, "接口检查失败", 14, Ui.text, Ui.medium(this)));
      TextView detail = Ui.text(this, Diagnostics.redact(this, error), 12, Ui.warn, null);
      detail.setLineSpacing(dp(3), 1f);
      LinearLayout.LayoutParams detailSpace = new LinearLayout.LayoutParams(-1, -2);
      detailSpace.topMargin = dp(3);
      box.addView(detail, detailSpace);
      Button retry = NativeUi.textButton(this, "重试", () -> render());
      retry.setTextColor(Ui.accent);
      box.addView(retry);
      card.addView(box);
    });

    NativeUi.section(this, body, "其他");
    LinearLayout more = Ui.group(body);
    Ui.rowItem(more, R.drawable.ic_cloud, "重新检查", "再次检查接口与工具连接", () -> render());
    Ui.rowItem(more, R.drawable.ic_log, "运行日志", "查看服务与检查输出", this::openLogs);
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
    summary = NativeUi.statusCard(this, body, "正在读取运行状态…");
    summary.setTextColor(Ui.text);
    summary.setTextSize(13);
    summary.setLineSpacing(Ui.dp(this, 4), 1f);
  }
  private String pageTitle() {
    if (page.equals("logs")) return "运行日志";
    if (page.equals("environment")) return "运行环境";
    if (page.equals("agents")) return "Agent 管理";
    if (page.equals("versions")) return agentName(selectedAgent) + " 版本";
    if (page.equals("updates")) return "Agent 更新";
    if (page.equals("interfaces")) return "接口检查";
    if (page.equals("device")) return "手机与开发工具";
    return "设置";
  }
  private void infoRow(LinearLayout group, int icon, String title, String value) {
    Ui.rowItem(group, icon, title, value, null);
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
    operationTitle = NativeUi.text(this, "", 13, NativeUi.TEXT);
    operationTitle.setTypeface(null, android.graphics.Typeface.BOLD);
    operationCard.addView(operationTitle);
    operationMessage = Ui.text(this, "", 11.5f, Ui.muted, null);
    operationMessage.setMaxLines(3);
    operationMessage.setEllipsize(android.text.TextUtils.TruncateAt.END);
    operationMessage.setTextIsSelectable(true);
    LinearLayout.LayoutParams messageSpace = new LinearLayout.LayoutParams(-1, -2);
    messageSpace.topMargin = dp(3);
    operationCard.addView(operationMessage, messageSpace);
    operationProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    operationProgress.setIndeterminate(true);
    LinearLayout.LayoutParams progressSpace = new LinearLayout.LayoutParams(-1, dp(3));
    progressSpace.topMargin = dp(6);
    operationCard.addView(operationProgress, progressSpace);
    operationLogs = Ui.text(this, "查看运行日志", 12, Ui.accent, Ui.medium(this));
    operationLogs.setPadding(0, dp(7), 0, 0);
    operationLogs.setOnClickListener(v -> {});
    operationCard.addView(operationLogs);
    operationDismiss = Ui.text(this, "收起结果", 12, Ui.muted, Ui.medium(this));
    operationDismiss.setPadding(0, dp(7), 0, dp(4));
    operationCard.addView(operationDismiss);
    operationCard.setVisibility(View.GONE);
  }
  private void showOperation(JSONObject data) {
    if (operationCard == null || data == null) return;
    String status = data.optString("status");
    String id = data.optString("id");
    boolean running = status.equals("running");
    boolean failed = status.equals("failed") || status.equals("interrupted");
    if (!OperationState.shouldDisplay(data, page, dismissedOperationId)) {
      operationCard.setVisibility(View.GONE); return;
    }
    operationCard.setVisibility(View.VISIBLE);
    long end = running ? System.currentTimeMillis() : data.optLong("finishedAt", data.optLong("updatedAt"));
    long seconds = Math.max(0, end - data.optLong("startedAt", end)) / 1000;
    operationTitle.setText((running ? "进行中" : failed ? "操作失败" : "已完成") + " · "
        + data.optString("title", "环境操作") + " · " + seconds + "s");
    String detail = data.optString("message", "");
    if (running) {
      org.json.JSONArray steps = data.optJSONArray("steps");
      if (steps != null && steps.length() > 0)
        detail = steps.optJSONObject(steps.length() - 1).optString("message", detail);
    }
    detail = detail.replaceAll("(?m)^AIXS_PROGRESS:.*$", "").trim();
    if (detail.isEmpty()) detail = running ? "正在执行…" : "操作已结束";
    operationMessage.setText(detail + (failed ? "\n详情请查看运行日志。" : ""));
    operationProgress.setVisibility(running ? View.VISIBLE : View.GONE);
    operationLogs.setVisibility(failed ? View.VISIBLE : View.GONE);
    operationLogs.setOnClickListener(v -> {
      logOperation = id; logChannel = "debug"; logSource = "all"; resetLogSnapshot(); show("logs");
    });
    operationDismiss.setVisibility(running ? View.GONE : View.VISIBLE);
    operationDismiss.setOnClickListener(v -> {
      dismissedOperationId = id;
      operationCard.setVisibility(View.GONE);
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
      String state = (manager.complete() ? "环境已就绪"
              : manager.initialized() ? "基础系统已就绪 · 待补装组件" : "运行环境未初始化")
          + " · " + installedCount() + " / 3 Agent";
      if (busy() || pendingOperation != null) state += " · 操作进行中";
      File status = new File(getFilesDir(), "runtime-operation.json");
      if (status.isFile()) {
        JSONObject data = RuntimeManager.json(status);
        if (pendingOperation != null && data.optString("id").equals(pendingOperation.optString("id"))) pendingOperation = null;
        showOperation(pendingOperation == null ? data : pendingOperation);
      }
      if (pendingOperation != null) showOperation(pendingOperation);
      if (summary != null && !state.contentEquals(summary.getText()))
        summary.setText(state);
      updateAgentRows();
      readAgentStatus();
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
    state.putString("dismissedOperationId", dismissedOperationId);
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
