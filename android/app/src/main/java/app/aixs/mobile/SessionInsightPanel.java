package app.aixs.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import org.json.JSONObject;

/**
 * 会话详情：右上角入口打开的常驻面板，按文件 / 子代理 / 来源三栏展示本次
 * 会话实际发生的事。数据来自会话记录本身，面板不推测 Agent 没有报告的内容。
 */
final class SessionInsightPanel {
  interface Host {
    void dismiss();
    void openEditor(String absolutePath);
    void revealTurn(int turnNumber);
  }

  private static final String TAB_FILES = "files";
  private static final String TAB_AGENTS = "agents";
  private static final String TAB_SOURCES = "sources";
  private static final int MAX_FILE_ROWS = 300;
  private static final int MAX_SOURCE_ROWS = 240;

  private final Activity activity;
  private final Host host;
  private final LinearLayout root;
  private final TextView conversation;
  private final TextView meta;
  private final LinearLayout firstTaskRow;
  private final TextView firstTaskLabel;
  private final TextView firstTaskBody;
  private final ImageView firstTaskChevron;
  private final TextView livePill;
  private final LinearLayout tabTrack;
  private final LinearLayout list;
  private final ScrollView scroll;
  private final TextView footer;
  private final TextView[] tabLabels = new TextView[3];
  private final ArrayList<LinearLayout> tabSegments = new ArrayList<>();
  private final HashSet<String> expanded = new HashSet<>();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable ticker = new Runnable() {
    @Override public void run() {
      if (!attached()) return;
      if (insight != null && (insight.activeSubagents > 0 || running)) {
        rebuild(false);
        handler.postDelayed(this, 1000L);
      }
    }
  };

  private JSONObject thread;
  private SessionInsight insight;
  private String tab = TAB_FILES;
  private String signature = "";
  private String paintedTabs = "";
  private boolean running;
  private boolean firstTaskOpen;

  SessionInsightPanel(Activity activity, Host host) {
    this.activity = activity;
    this.host = host;
    this.root = Ui.col(activity);
    this.root.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 2), Ui.dp(activity, 20), 0);

    LinearLayout header = Ui.row(activity);
    TextView heading = Ui.text(activity, "会话详情", 18, Ui.text, Ui.medium(activity));
    header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));
    livePill = Ui.pill(activity, "", Ui.accent, Ui.accentSoft);
    livePill.setTextSize(10.5f);
    livePill.setVisibility(View.GONE);
    header.addView(livePill);
    LinearLayout close = Ui.iconButton(activity, R.drawable.ic_close, "关闭会话详情", () -> host.dismiss());
    LinearLayout.LayoutParams closeSpace = Ui.w(Ui.dp(activity, 44), Ui.dp(activity, 44));
    closeSpace.leftMargin = Ui.dp(activity, 4);
    header.addView(close, closeSpace);
    root.addView(header);

    conversation = Ui.text(activity, "", 14.5f, Ui.text, Ui.medium(activity));
    conversation.setMaxLines(2);
    conversation.setEllipsize(TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams conversationSpace = Ui.fill();
    conversationSpace.topMargin = Ui.dp(activity, 2);
    root.addView(conversation, conversationSpace);

    meta = Ui.text(activity, "", 11.5f, Ui.faint, null);
    meta.setMaxLines(2);
    meta.setEllipsize(TextUtils.TruncateAt.END);
    meta.setLineSpacing(Ui.dp(activity, 2), 1f);
    LinearLayout.LayoutParams metaSpace = Ui.fill();
    metaSpace.topMargin = Ui.dp(activity, 4);
    root.addView(meta, metaSpace);

    // The first prompt is conversation content, not UI copy. Keep it available but collapsed,
    // so a Chinese panel never reads like an untranslated English screen.
    firstTaskRow = Ui.row(activity);
    firstTaskRow.setGravity(16);
    firstTaskLabel = Ui.text(activity, "查看首条任务原文", 11.5f, Ui.faint, Ui.medium(activity));
    firstTaskRow.addView(firstTaskLabel, new LinearLayout.LayoutParams(0, -2, 1f));
    firstTaskChevron = new ImageView(activity);
    firstTaskChevron.setImageResource(R.drawable.ic_chevron);
    firstTaskChevron.setColorFilter(Ui.faint);
    firstTaskRow.addView(firstTaskChevron, Ui.w(Ui.dp(activity, 13), Ui.dp(activity, 13)));
    Ui.pad(firstTaskRow, 2, 5, 2, 2);
    firstTaskBody = Ui.text(activity, "", 12f, Ui.muted, null);
    firstTaskBody.setLineSpacing(Ui.dp(activity, 3), 1f);
    firstTaskBody.setMaxLines(8);
    firstTaskBody.setEllipsize(TextUtils.TruncateAt.END);
    firstTaskBody.setVisibility(View.GONE);
    Ui.pad(firstTaskBody, 2, 0, 2, 3);
    Ui.touchable(firstTaskRow);
    Ui.rippleRounded(firstTaskRow, 8, Ui.text);
    firstTaskRow.setOnClickListener(v -> {
      firstTaskOpen = firstTaskBody.getVisibility() != View.VISIBLE;
      firstTaskBody.setVisibility(firstTaskOpen ? View.VISIBLE : View.GONE);
      firstTaskLabel.setText(firstTaskOpen ? "收起首条任务原文" : "查看首条任务原文");
      firstTaskChevron.setImageResource(firstTaskOpen ? R.drawable.ic_expand : R.drawable.ic_chevron);
      if (firstTaskOpen) Ui.enter(firstTaskBody, 0);
    });
    root.addView(firstTaskRow);
    root.addView(firstTaskBody);

    tabTrack = Ui.row(activity);
    tabTrack.setBackground(Ui.round(Ui.cardHigh, Ui.dp(activity, 999)));
    tabTrack.setPadding(Ui.dp(activity, 4), Ui.dp(activity, 4), Ui.dp(activity, 4), Ui.dp(activity, 4));
    String[] labels = {"文件", "子代理", "来源"};
    String[] ids = {TAB_FILES, TAB_AGENTS, TAB_SOURCES};
    for (int i = 0; i < ids.length; i++) {
      final String id = ids[i];
      LinearLayout segment = new LinearLayout(activity);
      segment.setGravity(Gravity.CENTER);
      segment.setMinimumHeight(Ui.dp(activity, 34));
      TextView label = Ui.text(activity, labels[i], 12.5f, Ui.muted, Ui.medium(activity));
      label.setSingleLine(true);
      segment.addView(label);
      Ui.touchable(segment);
      segment.setOnClickListener(v -> switchTab(id));
      tabTrack.addView(segment, new LinearLayout.LayoutParams(0, -2, 1f));
      tabLabels[i] = label;
      tabSegments.add(segment);
    }
    LinearLayout.LayoutParams trackSpace = Ui.fill();
    trackSpace.topMargin = Ui.dp(activity, 12);
    root.addView(tabTrack, trackSpace);

    scroll = new ScrollView(activity);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setClipToPadding(false);
    list = Ui.col(activity);
    list.setPadding(0, Ui.dp(activity, 6), 0, Ui.dp(activity, 20));
    scroll.addView(list);
    LinearLayout.LayoutParams scrollSpace = new LinearLayout.LayoutParams(-1, 0, 1f);
    scrollSpace.topMargin = Ui.dp(activity, 2);
    root.addView(scroll, scrollSpace);

    footer = Ui.text(activity, "", 10.5f, Ui.faint, null);
    footer.setVisibility(View.GONE);
    LinearLayout.LayoutParams footerSpace = Ui.fill();
    footerSpace.setMargins(0, 0, 0, Ui.dp(activity, 10));
    root.addView(footer, footerSpace);

    paintTabs();
  }

  View build() { return root; }

  void update(JSONObject value) { update(value, SessionInsight.of(value)); }

  void update(JSONObject value, SessionInsight computed) {
    this.thread = value;
    this.insight = computed != null ? computed : SessionInsight.of(value);
    this.running = insight != null && insight.turns > 0 && hasRunningTurn(value);
    paintHeader();
    paintTabs();
    String next = (insight == null ? "" : insight.signature()) + "|" + tab + "|" + expanded.hashCode() + "|" + tick();
    if (!next.equals(signature)) rebuild(false);
    schedule();
  }

  void release() {
    handler.removeCallbacks(ticker);
  }

  private boolean attached() { return root.getWindowToken() != null; }

  private void schedule() {
    handler.removeCallbacks(ticker);
    if (insight != null && (insight.activeSubagents > 0 || running)) handler.postDelayed(ticker, 1000L);
  }

  private long tick() {
    return insight == null || insight.activeSubagents == 0 ? 0 : System.currentTimeMillis() / 1000L;
  }

  private static boolean hasRunningTurn(JSONObject value) {
    org.json.JSONArray turns = value == null ? null : value.optJSONArray("turns");
    for (int i = 0; turns != null && i < turns.length(); i++) {
      JSONObject turn = turns.optJSONObject(i);
      if (turn != null && SessionInsight.active(ConversationContent.string(turn, "status", ""))) return true;
    }
    return false;
  }

  private void paintHeader() {
    if (insight == null) return;
    StringBuilder overview = new StringBuilder();
    if (!insight.files.isEmpty()) append(overview, insight.files.size() + " 个文件变化");
    if (!insight.subagents.isEmpty()) append(overview, insight.subagents.size() + " 个子代理");
    if (!insight.sources.isEmpty()) append(overview, insight.sources.size() + " 项来源");
    boolean chineseTitle = !insight.title.isEmpty() && hasChinese(insight.title);
    String overviewText;
    if (overview.length() > 0) overviewText = overview.toString();
    else if (insight.turns > 0) overviewText = "共 " + insight.turns + " 轮，还没有文件或子代理记录";
    else overviewText = "本次会话暂无记录";
    conversation.setText(chineseTitle ? insight.title : overviewText);
    firstTaskRow.setVisibility(!chineseTitle && !insight.title.isEmpty() ? View.VISIBLE : View.GONE);
    if (!chineseTitle && !insight.title.isEmpty()) {
      firstTaskBody.setText(insight.title);
      firstTaskBody.setVisibility(firstTaskOpen ? View.VISIBLE : View.GONE);
      firstTaskLabel.setText(firstTaskOpen ? "收起首条任务原文" : "查看首条任务原文");
      firstTaskChevron.setImageResource(firstTaskOpen ? R.drawable.ic_expand : R.drawable.ic_chevron);
    } else if (chineseTitle) {
      firstTaskBody.setVisibility(View.GONE);
    }
    StringBuilder line = new StringBuilder();
    if (!insight.cwd.isEmpty()) line.append(NativeHomeActivity.folder(insight.cwd));
    if (!insight.engine.isEmpty()) append(line, NativeUi.engineName(insight.engine) + (insight.model.isEmpty() ? "" : " · " + NativeHomeActivity.shortModel(insight.model)));
    if (insight.turns > 0) append(line, insight.turns + " 轮");
    if (insight.durationMs > 0) append(line, "耗时 " + SessionInsight.duration(insight.durationMs));
    if (insight.tokens > 0) append(line, "词元 " + SessionInsight.tokens(insight.tokens));
    meta.setText(line.toString());
    if (running || insight.activeSubagents > 0) {
      livePill.setVisibility(View.VISIBLE);
      livePill.setText(running ? (insight.activeSubagents > 0 ? "运行中 · " + insight.activeSubagents + " 个子代理" : "运行中") : insight.activeSubagents + " 个子代理进行中");
      livePill.setTextColor(Ui.onAccent);
      livePill.setBackground(Ui.round(Ui.accent, 999f));
    } else {
      livePill.setVisibility(View.GONE);
    }
  }

  private static boolean hasChinese(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c >= 0x4E00 && c <= 0x9FFF) return true;
    }
    return false;
  }

  private static void append(StringBuilder builder, String value) {
    if (value == null || value.isEmpty()) return;
    if (builder.length() > 0) builder.append(" · ");
    builder.append(value);
  }

  private void paintTabs() {
    int[] counts = new int[3];
    if (insight != null) {
      counts[0] = insight.files.size();
      counts[1] = insight.subagents.size();
      counts[2] = insight.sources.size();
    }
    String[] names = {"文件", "子代理", "来源"};
    String[] ids = {TAB_FILES, TAB_AGENTS, TAB_SOURCES};
    String state = counts[0] + "/" + counts[1] + "/" + counts[2] + "/" + tab;
    if (state.equals(paintedTabs)) return;
    paintedTabs = state;
    for (int i = 0; i < tabLabels.length; i++) {
      boolean selected = ids[i].equals(tab);
      String label = counts[i] > 0 ? names[i] + " " + counts[i] : names[i];
      tabLabels[i].setText(label);
      tabLabels[i].setTextColor(selected ? Ui.text : Ui.muted);
      tabSegments.get(i).setBackground(Ui.round(selected ? Ui.card : 0, 999f));
      if (!selected) Ui.rippleRounded(tabSegments.get(i), 999, Ui.muted);
      tabSegments.get(i).setContentDescription(names[i] + "，共 " + counts[i] + " 项" + (selected ? "，已选中" : ""));
    }
  }

  private void switchTab(String value) {
    if (tab.equals(value)) return;
    tab = value;
    expanded.clear();
    signature = "";
    paintTabs();
    scroll.post(() -> scroll.scrollTo(0, 0));
    rebuild(true);
  }

  private void rebuild(boolean resetScroll) {
    if (insight == null) {
      return;
    }
    signature = insight.signature() + "|" + tab + "|" + expanded.hashCode() + "|" + tick();
    int position = scroll.getScrollY();
    list.removeAllViews();
    footer.setVisibility(View.GONE);
    if (tab.equals(TAB_FILES)) files();
    else if (tab.equals(TAB_AGENTS)) agents();
    else sources();
    if (!resetScroll && position > 0) scroll.post(() -> scroll.scrollTo(0, Math.min(position, Math.max(0, list.getHeight() - scroll.getHeight()))));
  }

  // ---- files -------------------------------------------------------------
  private void files() {
    if (insight.files.isEmpty()) {
      Ui.empty(list, R.drawable.ic_file, "本会话还没有文件变化", running ? "本轮结束后会按项目文件清单汇总新增、修改和删除。" : "发送任务后，Agent 实际改动的文件会汇总在这里。");
      if (!insight.ignored.isEmpty()) note("未扫描：" + TextUtils.join("、", insight.ignored));
      return;
    }
    list.addView(statStrip());
    boolean live = running && hasProvisionalChanges();
    if (live) note("本轮仍在运行：先显示工具上报的编辑，结束后替换为项目清单的净变化。");
    section("新增 / 生成", "按项目清单汇总；包含命令生成的文件，不把改动归给单个工具。", "created");
    section("修改", null, "modified");
    section("删除", null, "deleted");
    if (insight.files.size() > MAX_FILE_ROWS) note("共 " + insight.files.size() + " 个路径，仅显示前 " + MAX_FILE_ROWS + " 项。");
    if (!insight.ignored.isEmpty()) note("未扫描依赖、缓存或内部目录：" + TextUtils.join("、", insight.ignored));
  }

  private boolean hasProvisionalChanges() {
    for (SessionInsight.File file : insight.files) if ("agent".equals(file.verification)) return true;
    return false;
  }

  private View statStrip() {
    LinearLayout row = Ui.row(activity);
    row.setBackground(Ui.round(activity, Ui.card, 16, 0.8f, Ui.lineSoft));
    row.setPadding(Ui.dp(activity, 6), Ui.dp(activity, 10), Ui.dp(activity, 6), Ui.dp(activity, 10));
    LinearLayout.LayoutParams rowSpace = Ui.fill();
    rowSpace.bottomMargin = Ui.dp(activity, 6);
    row.setLayoutParams(rowSpace);
    stat(row, insight.created, "新增", Ui.mint);
    stat(row, insight.modified, "修改", Ui.accent);
    stat(row, insight.deleted, "删除", Ui.danger);
    return row;
  }

  private void stat(LinearLayout parent, int count, String label, int color) {
    LinearLayout cell = Ui.col(activity);
    cell.setGravity(Gravity.CENTER);
    TextView number = Ui.text(activity, String.valueOf(count), 19, count > 0 ? color : Ui.faint, Ui.medium(activity));
    number.setGravity(Gravity.CENTER);
    cell.addView(number);
    TextView caption = Ui.text(activity, label, 10.5f, Ui.faint, null);
    caption.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams captionSpace = Ui.fill();
    captionSpace.topMargin = Ui.dp(activity, 2);
    cell.addView(caption, captionSpace);
    parent.addView(cell, new LinearLayout.LayoutParams(0, -2, 1f));
  }

  private void section(String label, String hint, String kind) {
    ArrayList<SessionInsight.File> group = new ArrayList<>();
    for (SessionInsight.File file : insight.files) if (file.kind.equals(kind)) group.add(file);
    if (group.isEmpty()) return;
    LinearLayout heading = Ui.row(activity);
    TextView title = Ui.text(activity, label, 12, Ui.muted, Ui.medium(activity));
    heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
    TextView count = Ui.pill(activity, String.valueOf(group.size()), Ui.faint, Ui.cardHigh);
    heading.addView(count);
    LinearLayout.LayoutParams headingSpace = Ui.fill();
    headingSpace.setMargins(Ui.dp(activity, 2), Ui.dp(activity, 10), Ui.dp(activity, 2), Ui.dp(activity, 4));
    list.addView(heading, headingSpace);
    if (hint != null) note(hint);
    int shown = 0;
    for (SessionInsight.File file : group) {
      if (shown >= MAX_FILE_ROWS) break;
      list.addView(fileRow(file));
      ++shown;
    }
  }

  private View fileRow(SessionInsight.File file) {
    LinearLayout row = Ui.row(activity);
    row.setBackground(Ui.round(activity, Ui.card, 12, 0.7f, Ui.lineSoft));
    row.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 9), Ui.dp(activity, 10), Ui.dp(activity, 9));
    LinearLayout.LayoutParams space = Ui.fill();
    space.bottomMargin = Ui.dp(activity, 4);
    row.setLayoutParams(space);
    View dot = new View(activity);
    dot.setBackground(Ui.round(file.kind.equals("created") ? Ui.mint : file.kind.equals("deleted") ? Ui.danger : Ui.accent, Ui.dp(activity, 3)));
    LinearLayout.LayoutParams dotSpace = Ui.w(Ui.dp(activity, 6), Ui.dp(activity, 6));
    dotSpace.rightMargin = Ui.dp(activity, 10);
    row.addView(dot, dotSpace);
    LinearLayout labels = Ui.col(activity);
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
    TextView path = Ui.text(activity, file.path, 12.5f, Ui.text, null);
    path.setMaxLines(2);
    path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    LinearLayout labelRow = Ui.row(activity);
    labelRow.setPadding(0, Ui.dp(activity, 2), 0, Ui.dp(activity, 2));
    labelRow.addView(path, new LinearLayout.LayoutParams(-1, -2));
    labels.addView(labelRow, Ui.fill());
    StringBuilder detail = new StringBuilder();
    if (file.turn > 0) detail.append("第 ").append(file.turn).append(" 轮");
    if (file.occurrences > 1) {
      if (detail.length() > 0) detail.append(" · ");
      detail.append("变更 ").append(file.occurrences).append(" 次");
    }
    if (kindText(file.verification).length() > 0) {
      if (detail.length() > 0) detail.append(" · ");
      detail.append(kindText(file.verification));
    }
    if (detail.length() > 0) {
      TextView note = Ui.text(activity, detail.toString(), 10.5f, Ui.faint, null);
      note.setSingleLine(true);
      note.setEllipsize(TextUtils.TruncateAt.END);
      LinearLayout.LayoutParams noteSpace = Ui.fill();
      noteSpace.topMargin = Ui.dp(activity, 2);
      labels.addView(note, noteSpace);
    }
    if (!file.members.isEmpty()) {
      TextView who = Ui.text(activity, "成员 " + TextUtils.join("、", file.members), 10.0f, Ui.accent, null);
      who.setMaxLines(2);
      who.setEllipsize(TextUtils.TruncateAt.END);
      LinearLayout.LayoutParams whoSpace = Ui.fill();
      whoSpace.topMargin = Ui.dp(activity, 2);
      labels.addView(who, whoSpace);
    }
    ImageView more = new ImageView(activity);
    more.setImageResource(R.drawable.ic_more);
    more.setColorFilter(Ui.faint);
    more.setAlpha(0.8f);
    LinearLayout.LayoutParams moreSpace = Ui.w(Ui.dp(activity, 16), Ui.dp(activity, 16));
    moreSpace.leftMargin = Ui.dp(activity, 8);
    row.addView(more, moreSpace);
    Ui.touchable(row);
    Ui.rippleRounded(row, 12, Ui.text);
    row.setOnClickListener(v -> fileActions(file));
    row.setContentDescription(fileKindName(file.kind) + " " + file.path + "，点按查看操作");
    return row;
  }

  private static String fileKindName(String kind) {
    if ("created".equals(kind)) return "新增";
    if ("deleted".equals(kind)) return "删除";
    if ("modified".equals(kind)) return "修改";
    return "文件";
  }

  private static String sourceKindName(String kind) {
    if ("file".equals(kind)) return "文件";
    if ("web".equals(kind)) return "网页";
    if ("search".equals(kind)) return "搜索";
    if ("citation".equals(kind)) return "引用";
    return "来源";
  }

  private static String kindText(String verification) {
    if (verification == null) return "";
    if (verification.equals("content")) return "内容校验";
    if (verification.equals("metadata")) return "元数据";
    if (verification.equals("filesystem")) return "文件系统";
    if (verification.equals("agent")) return "Agent 上报";
    return "";
  }

  private void fileActions(SessionInsight.File file) {
    String absolute = absolute(file.path, file.root);
    ArrayList<String> actions = new ArrayList<>();
    if (file.turn > 0) actions.add("在会话中定位");
    actions.add("复制路径");
    if (!file.kind.equals("deleted") && !absolute.isEmpty()) actions.add("打开文件");
    if (!absolute.isEmpty()) actions.add("打开所在项目");
    new MaterialAlertDialogBuilder(activity)
        .setTitle(lastSegment(file.path))
        .setItems(actions.toArray(new String[0]), (dialog, which) -> {
          String action = actions.get(which);
          if (action.equals("复制路径")) copy(file.path);
          else if (action.equals("在会话中定位")) host.revealTurn(file.turn);
          else if (action.equals("打开文件")) host.openEditor(absolute);
          else if (action.equals("打开所在项目")) openProject(file.path, file.root);
        })
        .setNegativeButton("取消", null)
        .show();
  }

  private void openProject(String path, String root) {
    try {
      String cwd = insight == null || insight.cwd.isEmpty() ? "/" : insight.cwd;
      String engine = insight == null || insight.engine.isEmpty() ? "codex" : insight.engine;
      activity.startActivity(new Intent(activity, NativeWorkspaceActivity.class)
          .putExtra("engine", engine).putExtra("cwd", cwd).putExtra("file", absolute(path, root)));
    } catch (Exception error) {
      NativeUi.toast(activity, "无法打开编辑器：" + error.getMessage());
    }
  }

  private String absolute(String path) {
    return absolute(path, "");
  }

  private String absolute(String path, String root) {
    if (path == null || path.isEmpty()) return "";
    if (path.startsWith("/")) return path;
    if (root != null && !root.isEmpty()) return new java.io.File(root, path).getAbsolutePath();
    String cwd = insight == null ? "" : insight.cwd;
    if (cwd.isEmpty()) return "";
    return cwd.endsWith("/") ? cwd + path : cwd + "/" + path;
  }

  private static String lastSegment(String path) {
    String value = path == null ? "" : path.replace('\\', '/');
    int cut = value.lastIndexOf('/');
    return cut >= 0 ? value.substring(cut + 1) : value;
  }

  // ---- subagents ---------------------------------------------------------
  private void agents() {
    if (insight.subagents.isEmpty()) {
      Ui.empty(list, R.drawable.ic_spark, "本会话还没有子代理", "Codex 子线程、Claude 子任务和 DeepSeek 子会话会显示在这里，并随执行进度更新。");
      return;
    }
    note("共 " + insight.subagents.size() + " 个子代理" + (insight.activeSubagents > 0 ? "，运行中 " + insight.activeSubagents + " 个" : "")
        + "。来自协议中的真实父子关系，不按目录或模型猜测归属。");
    for (SessionInsight.Subagent child : insight.subagents) list.addView(agentCard(child));
  }

  private View agentCard(SessionInsight.Subagent child) {
    boolean open = expanded.contains(child.key);
    LinearLayout card = Ui.col(activity);
    card.setBackground(Ui.round(activity, Ui.card, 16, 0.8f, Ui.lineSoft));
    LinearLayout.LayoutParams cardSpace = Ui.fill();
    cardSpace.bottomMargin = Ui.dp(activity, 8);
    card.setLayoutParams(cardSpace);

    LinearLayout header = Ui.row(activity);
    header.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 10), Ui.dp(activity, 12), Ui.dp(activity, 8));
    View dot = new View(activity);
    dot.setBackground(Ui.round(statusColor(child), Ui.dp(activity, 3)));
    LinearLayout.LayoutParams dotSpace = Ui.w(Ui.dp(activity, 7), Ui.dp(activity, 7));
    dotSpace.rightMargin = Ui.dp(activity, 9);
    header.addView(dot, dotSpace);
    TextView title = Ui.text(activity, child.title, 13.5f, Ui.text, Ui.medium(activity));
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
    TextView status = Ui.pill(activity, statusText(child), statusColor(child), Ui.cardHigh);
    header.addView(status);
    ImageView chevron = new ImageView(activity);
    chevron.setImageResource(open ? R.drawable.ic_expand : R.drawable.ic_chevron);
    chevron.setColorFilter(Ui.faint);
    LinearLayout.LayoutParams chevronSpace = Ui.w(Ui.dp(activity, 16), Ui.dp(activity, 16));
    chevronSpace.leftMargin = Ui.dp(activity, 6);
    header.addView(chevron, chevronSpace);
    card.addView(header);

    LinearLayout meta = Ui.row(activity);
    meta.setPadding(Ui.dp(activity, 12), 0, Ui.dp(activity, 12), Ui.dp(activity, 8));
    TextView metaText = Ui.text(activity, agentMeta(child), 11, Ui.faint, null);
    metaText.setSingleLine(true);
    metaText.setEllipsize(TextUtils.TruncateAt.END);
    meta.addView(metaText, new LinearLayout.LayoutParams(0, -2, 1f));
    card.addView(meta);

    if (!child.preview.isEmpty()) {
      TextView preview = Ui.text(activity, child.preview, 12, Ui.muted, null);
      preview.setMaxLines(open ? 3 : 2);
      preview.setEllipsize(TextUtils.TruncateAt.END);
      preview.setLineSpacing(Ui.dp(activity, 2), 1f);
      LinearLayout.LayoutParams previewSpace = Ui.fill();
      previewSpace.setMargins(Ui.dp(activity, 12), 0, Ui.dp(activity, 12), Ui.dp(activity, 8));
      card.addView(preview, previewSpace);
    }

    if (open) {
      LinearLayout log = Ui.col(activity);
      log.setPadding(Ui.dp(activity, 12), 0, Ui.dp(activity, 12), 0);
      int start = Math.max(0, child.log.size() - 24);
      for (int i = start; i < child.log.size(); i++) {
        SessionInsight.ChildActivity entry = child.log.get(i);
        TextView label = Ui.text(activity, entry.label, 10.5f, SessionInsight.active(entry.status) ? Ui.accent : Ui.muted, Ui.medium(activity));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        log.addView(label);
        if (!entry.detail.trim().isEmpty()) {
          TextView detail = Ui.text(activity, entry.detail, 11.5f, Ui.faint, null);
          detail.setMaxLines(5);
          detail.setEllipsize(TextUtils.TruncateAt.END);
          detail.setLineSpacing(Ui.dp(activity, 1), 1f);
          LinearLayout.LayoutParams detailSpace = Ui.fill();
          detailSpace.bottomMargin = Ui.dp(activity, 6);
          log.addView(detail, detailSpace);
        }
      }
      if (start > 0) {
        TextView more = Ui.text(activity, "仅显示最近 24 条子任务事件。", 10.5f, Ui.faint, null);
        log.addView(more);
      }
      card.addView(log);
    }

    LinearLayout actions = Ui.row(activity);
    actions.setPadding(Ui.dp(activity, 8), 0, Ui.dp(activity, 8), Ui.dp(activity, 4));
    actions.addView(ghost(open ? "收起过程" : "展开过程 · " + child.log.size(), open ? R.drawable.ic_expand : R.drawable.ic_chevron, () -> {
      if (open) expanded.remove(child.key); else expanded.add(child.key);
      signature = "";
      rebuild(false);
    }));
    if (child.turn > 0) {
      actions.addView(ghost("在会话中定位", R.drawable.ic_history, () -> host.revealTurn(child.turn)));
    }
    card.addView(actions);
    Ui.touchable(header);
    Ui.rippleRounded(header, 12, Ui.text);
    header.setOnClickListener(v -> {
      if (open) expanded.remove(child.key); else expanded.add(child.key);
      signature = "";
      rebuild(false);
    });
    return card;
  }

  private String agentMeta(SessionInsight.Subagent child) {
    StringBuilder line = new StringBuilder();
    if (!child.engine.isEmpty()) line.append(NativeUi.engineName(child.engine));
    if (!child.model.isEmpty()) append(line, NativeHomeActivity.shortModel(child.model));
    if (child.tools > 0) append(line, "工具 " + child.tools);
    if (child.messages > 0) append(line, "回复 " + child.messages);
    if (child.reasoning > 0) append(line, "思考 " + child.reasoning);
    if (child.durationMs > 0) append(line, SessionInsight.duration(child.durationMs));
    else if (child.active() && child.startedAtMs > 0) {
      long elapsed = System.currentTimeMillis() - child.startedAtMs;
      if (elapsed > 0 && elapsed < 24L * 3600 * 1000) append(line, "已运行 " + SessionInsight.duration(elapsed));
    }
    if (child.summaryOnly && child.messages > 0) append(line, "部分明细未提供");
    if (line.length() == 0) line.append("子代理");
    return line.toString();
  }

  private String statusText(SessionInsight.Subagent child) {
    String status = SessionInsight.statusLabel(child.status);
    if (child.active() && child.summaryOnly) return status;
    return status;
  }

  private int statusColor(SessionInsight.Subagent child) {
    if (child.status.equals("failed")) return Ui.danger;
    if (child.status.equals("interrupted") || child.status.equals("discarded")) return Ui.warn;
    if (child.active()) return Ui.accent;
    return Ui.mint;
  }

  // ---- sources -----------------------------------------------------------
  private void sources() {
    if (insight.sources.isEmpty()) {
      Ui.empty(list, R.drawable.ic_search, "本会话还没有可展示的来源", "读取、搜索、网页和引用会从工具参数与会话引用中整理；没有上报参数的工具不会出现在这里。");
      return;
    }
    int shown = 0;
    String[][] groups = {
        {"file", "读取的文件", "工具明确读取、列出或定位的项目文件。"},
        {"search", "搜索", "工具参数中的搜索关键词。"},
        {"web", "网页", "打开或抓取的链接。"},
        {"citation", "引用", "Agent 回复携带的文件引用。"},
        {"attachment", "附件", "本轮消息附带的图片。"},
    };
    for (String[] group : groups) {
      ArrayList<SessionInsight.Source> rows = new ArrayList<>();
      for (SessionInsight.Source source : insight.sources) if (source.kind.equals(group[0])) rows.add(source);
      if (rows.isEmpty()) continue;
      heading(group[1], rows.size(), group[2]);
      for (SessionInsight.Source source : rows) {
        if (shown >= MAX_SOURCE_ROWS) break;
        list.addView(sourceRow(source));
        ++shown;
      }
    }
    if (insight.sources.size() > MAX_SOURCE_ROWS) note("共 " + insight.sources.size() + " 项来源，仅显示前 " + MAX_SOURCE_ROWS + " 项。");
    note("来源按会话记录和工具参数整理；模型未上报或由 shell 自行读取的路径可能缺失。");
  }

  private void heading(String label, int count, String hint) {
    LinearLayout row = Ui.row(activity);
    TextView title = Ui.text(activity, label, 12, Ui.muted, Ui.medium(activity));
    row.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
    row.addView(Ui.pill(activity, String.valueOf(count), Ui.faint, Ui.cardHigh));
    LinearLayout.LayoutParams space = Ui.fill();
    space.setMargins(Ui.dp(activity, 2), Ui.dp(activity, 10), Ui.dp(activity, 2), Ui.dp(activity, 4));
    list.addView(row, space);
    note(hint);
  }

  private View sourceRow(SessionInsight.Source source) {
    LinearLayout row = Ui.row(activity);
    row.setBackground(Ui.round(activity, Ui.card, 12, 0.7f, Ui.lineSoft));
    row.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 9), Ui.dp(activity, 10), Ui.dp(activity, 9));
    LinearLayout.LayoutParams space = Ui.fill();
    space.bottomMargin = Ui.dp(activity, 4);
    row.setLayoutParams(space);
    ImageView icon = new ImageView(activity);
    icon.setImageResource(source.kind.equals("web") ? R.drawable.ic_cloud
        : source.kind.equals("search") ? R.drawable.ic_search
        : source.kind.equals("citation") ? R.drawable.ic_quote
        : source.kind.equals("attachment") ? R.drawable.ic_image : R.drawable.ic_file);
    icon.setColorFilter(Ui.faint);
    LinearLayout.LayoutParams iconSpace = Ui.w(Ui.dp(activity, 15), Ui.dp(activity, 15));
    iconSpace.rightMargin = Ui.dp(activity, 9);
    row.addView(icon, iconSpace);
    LinearLayout labels = Ui.col(activity);
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
    TextView label = Ui.text(activity, source.label, 12.5f, Ui.text, null);
    label.setMaxLines(source.kind.equals("citation") ? 2 : 2);
    label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    LinearLayout labelRow = Ui.row(activity);
    labelRow.setPadding(0, Ui.dp(activity, 2), 0, Ui.dp(activity, 2));
    labelRow.addView(label, new LinearLayout.LayoutParams(-1, -2));
    labels.addView(labelRow, Ui.fill());
    String detail = source.detail == null ? "" : source.detail.trim();
    if (!detail.isEmpty() || source.turn > 0) {
      StringBuilder line = new StringBuilder();
      if (source.turn > 0) line.append("第 ").append(source.turn).append(" 轮");
      if (source.occurrences > 1) {
        if (line.length() > 0) line.append(" · ");
        line.append("出现 ").append(source.occurrences).append(" 次");
      }
      if (!detail.isEmpty()) {
        if (line.length() > 0) line.append(" · ");
        line.append(SessionInsight.oneLine(detail, 120));
      }
      TextView note = Ui.text(activity, line.toString(), 10.5f, Ui.faint, null);
      note.setMaxLines(2);
      note.setEllipsize(TextUtils.TruncateAt.END);
      LinearLayout.LayoutParams noteSpace = Ui.fill();
      noteSpace.topMargin = Ui.dp(activity, 2);
      labels.addView(note, noteSpace);
    }
    ImageView more = new ImageView(activity);
    more.setImageResource(R.drawable.ic_more);
    more.setColorFilter(Ui.faint);
    more.setAlpha(0.8f);
    LinearLayout.LayoutParams moreSpace = Ui.w(Ui.dp(activity, 16), Ui.dp(activity, 16));
    moreSpace.leftMargin = Ui.dp(activity, 8);
    row.addView(more, moreSpace);
    Ui.touchable(row);
    Ui.rippleRounded(row, 12, Ui.text);
    row.setOnClickListener(v -> sourceActions(source));
    row.setContentDescription(sourceKindName(source.kind) + " " + source.label + "，点按查看操作");
    return row;
  }

  private void sourceActions(SessionInsight.Source source) {
    if (source.kind.equals("web")) {
      new MaterialAlertDialogBuilder(activity).setTitle("网页来源")
          .setItems(new String[] {"打开链接", "复制链接"}, (dialog, which) -> {
            if (which == 0) openUrl(source.label); else copy(source.label);
          }).setNegativeButton("取消", null).show();
      return;
    }
    if (source.kind.equals("search")) {
      copy(source.label);
      return;
    }
    if (source.kind.equals("attachment")) {
      copy(source.label);
      return;
    }
    SessionInsight.File file = new SessionInsight.File();
    file.path = source.label;
    file.turn = source.turn;
    file.kind = "modified";
    file.verification = source.kind;
    fileActions(file);
  }

  private void openUrl(String url) {
    try {
      activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (Exception error) {
      copy(url);
      NativeUi.toast(activity, "没有可打开链接的应用，链接已复制");
    }
  }

  private void copy(String value) {
    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Ai-XS", value));
    NativeUi.toast(activity, "已复制");
  }

  // ---- shared ------------------------------------------------------------
  private LinearLayout ghost(String label, int icon, Runnable click) {
    LinearLayout button = Ui.row(activity);
    button.setGravity(Gravity.CENTER);
    button.setBackground(Ui.round(Color.TRANSPARENT, Ui.dp(activity, 9)));
    Ui.ripple(button, Ui.muted);
    Ui.pad(button, 8, 0, 10, 0);
    if (icon != 0) {
      ImageView image = new ImageView(activity);
      image.setImageResource(icon);
      image.setColorFilter(Ui.faint);
      button.addView(image, Ui.w(Ui.dp(activity, 13), Ui.dp(activity, 13)));
      ((LinearLayout.LayoutParams) image.getLayoutParams()).setMarginEnd(Ui.dp(activity, 5));
    }
    button.addView(Ui.text(activity, label, 11.5f, Ui.faint, null));
    button.setMinimumHeight(Ui.dp(activity, 34));
    Ui.touchable(button);
    button.setOnClickListener(v -> click.run());
    return button;
  }

  private void note(String value) {
    TextView note = Ui.text(activity, value, 10.5f, Ui.faint, null);
    note.setLineSpacing(Ui.dp(activity, 2), 1f);
    LinearLayout.LayoutParams space = Ui.fill();
    space.setMargins(Ui.dp(activity, 4), 0, Ui.dp(activity, 4), Ui.dp(activity, 6));
    list.addView(note, space);
  }
}
