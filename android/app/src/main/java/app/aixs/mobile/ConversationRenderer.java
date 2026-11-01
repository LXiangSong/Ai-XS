package app.aixs.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.io.File;
import io.noties.markwon.Markwon;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.regex.*;

/**
 * Transcript typography: user words in a soft bubble, agent prose on the page,
 * tool and command runs folded into one-line cards.
 */
final class ConversationRenderer {
  private final Activity activity;
  private final Markwon markdown;
  private final String engineName;
  private static final Pattern FENCE = Pattern.compile("(?m)^(`{3,}|~{3,})([^\\n]*)\\n");

  ConversationRenderer(Activity activity, String engineName) {
    this.activity = activity;
    this.engineName = engineName;
    markdown = Markwon.create(activity);
  }
  private int dp(int value) {
    return Ui.dp(activity, value);
  }
  private TextView text(String value, float size, int color) {
    TextView view = new TextView(activity);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setIncludeFontPadding(false);
    view.setTextIsSelectable(true);
    view.setLineSpacing(dp(4), 1f);
    return view;
  }
  private void copy(String value) {
    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE);
    if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Ai-XS", value));
    Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show();
  }
  LinearLayout ghost(String label, int icon, Runnable handler) {
    LinearLayout button = Ui.row(activity);
    button.setGravity(Gravity.CENTER);
    button.setBackground(Ui.round(android.graphics.Color.TRANSPARENT, dp(9)));
    Ui.ripple(button, Ui.muted);
    Ui.pad(button, 8, 0, 10, 0);
    if (icon != 0) {
      ImageView image = new ImageView(activity);
      image.setImageResource(icon);
      image.setColorFilter(Ui.faint);
      button.addView(image, Ui.w(dp(13), dp(13)));
      ((LinearLayout.LayoutParams) image.getLayoutParams()).setMarginEnd(dp(5));
    }
    TextView caption = Ui.text(activity, label, 11.5f, Ui.faint, null);
    button.addView(caption);
    button.setMinimumHeight(dp(26));
    Ui.touchable(button);
    button.setOnClickListener(v -> handler.run());
    return button;
  }

  /** 发送后的引用消息：气泡上半部分是引用卡，下面是用户自己说的话。 */
  static final class Quoted {
    final String sender, body, rest;
    Quoted(String sender, String body, String rest) {
      this.sender = sender;
      this.body = body;
      this.rest = rest;
    }
  }

  /** 识别 “> 引用 来源：\n> 原文” 这种发送格式；普通的 “> xxx” 不当引用。 */
  static Quoted splitQuote(String value) {
    if (value == null || !value.startsWith(">")) return null;
    String[] lines = value.split("\n", -1);
    String sender = "";
    StringBuilder body = new StringBuilder();
    int i = 0;
    while (i < lines.length && lines[i].startsWith(">")) {
      String line = lines[i].substring(1).trim();
      if (i == 0) {
        if (!line.startsWith("引用")) return null;
        String tail = line.substring(2).trim();
        if (tail.startsWith("·")) tail = tail.substring(1).trim();
        int colon = tail.indexOf('：');
        if (colon < 0) colon = tail.indexOf(':');
        if (colon >= 0) tail = tail.substring(0, colon);
        sender = tail.trim();
      } else if (!line.isEmpty()) {
        if (body.length() > 0) body.append('\n');
        body.append(line);
      }
      i++;
    }
    if (body.length() == 0) return null;
    StringBuilder rest = new StringBuilder();
    while (i < lines.length) {
      if (rest.length() > 0) rest.append('\n');
      rest.append(lines[i]);
      i++;
    }
    return new Quoted(sender, body.toString(), rest.toString().trim());
  }

  private LinearLayout quoteCard(Quoted quoted, int contentWidth) {
    LinearLayout card = Ui.row(activity);
    card.setTag("quote");
    card.setBackground(Ui.round(activity, Ui.cardHigh, 9, 0.9f, Ui.lineSoft));
    Ui.pad(card, 8, 7, 10, 7);
    View accent = new View(activity);
    accent.setBackground(Ui.round(Ui.accent, dp(2)));
    card.addView(accent, new LinearLayout.LayoutParams(dp(3), -1));
    LinearLayout labels = Ui.col(activity);
    LinearLayout.LayoutParams labelsSpace = new LinearLayout.LayoutParams(0, -2, 1f);
    labelsSpace.leftMargin = dp(8);
    card.addView(labels, labelsSpace);
    TextView title = Ui.text(activity, quoted.sender.isEmpty() ? "引用" : "引用 · " + quoted.sender,
        10.5f, Ui.accent, Ui.medium(activity));
    title.setTextIsSelectable(false);
    labels.addView(title);
    TextView body = Ui.text(activity, quoted.body, 12.5f, Ui.muted, null);
    body.setTextIsSelectable(false);
    body.setMaxLines(2);
    body.setEllipsize(TextUtils.TruncateAt.END);
    body.setMaxWidth(Math.max(dp(80), contentWidth));
    LinearLayout.LayoutParams bodySpace = new LinearLayout.LayoutParams(-1, -2);
    bodySpace.topMargin = dp(2);
    labels.addView(body, bodySpace);
    card.setClickable(true);
    final long[] lastTap = {0L};
    card.setOnClickListener(view -> {
      long now = SystemClock.uptimeMillis();
      if (now - lastTap[0] <= 420L) {
        lastTap[0] = 0L;
        if (activity instanceof NativeHomeActivity) ((NativeHomeActivity) activity).revealQuoted(quoted.body);
      } else {
        lastTap[0] = now;
      }
    });
    return card;
  }

  /** User message: right-aligned bubble that never spans the full column. */
  void user(LinearLayout parent, String value) {
    user(parent, value, null, null);
  }
  void user(LinearLayout parent, String value, Runnable edit, Runnable quote) {
    LinearLayout row = Ui.col(activity);
    row.setGravity(Gravity.END);
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
    space.topMargin = dp(14);
    space.bottomMargin = dp(4);
    parent.addView(row, space);
    int width = activity.getResources().getDisplayMetrics().widthPixels;
    int maxWidth = (int) (width * 0.78f);
    Quoted quoted = splitQuote(value);
    String copyValue = quoted != null && !quoted.rest.isEmpty() ? quoted.rest : value;
    String spoken = quoted == null ? value : quoted.rest;
    if (!spoken.isEmpty()) {
      LinearLayout bubble = Ui.col(activity);
      bubble.setBackground(Ui.corners(Ui.accentSoft, dp(18), dp(18), dp(5), dp(18)));
      Ui.pad(bubble, 14, 11, 14, 11);
      TextView body = text(spoken, 15, Ui.onAccentSoft);
      body.setMaxWidth(maxWidth - dp(28));
      bubble.addView(body, new LinearLayout.LayoutParams(-2, -2));
      row.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
    }
    if (quoted != null) {
      /* 微信式：被引用的原文放在自己气泡下方，而不是塞进气泡里。 */
      LinearLayout card = quoteCard(quoted, maxWidth - dp(18));
      LinearLayout.LayoutParams cardSpace = new LinearLayout.LayoutParams(-2, -2);
      cardSpace.topMargin = dp(7);
      row.addView(card, cardSpace);
    }
    LinearLayout actions = Ui.row(activity);
    actions.setGravity(Gravity.END);
    actions.addView(ghost("复制", R.drawable.ic_copy, () -> copy(copyValue)));
    if (quote != null) actions.addView(ghost("引用", R.drawable.ic_quote, quote));
    if (edit != null) actions.addView(ghost("编辑", R.drawable.ic_edit, edit));
    parent.addView(actions, Ui.fill());
  }

  /** Agent answer: page prose with code blocks, folded tools and a copy affordance. */
  void assistant(LinearLayout parent, String value, boolean streaming) {
    assistant(parent, value, streaming, "");
  }

  /** {@code footer} carries this turn's token count and elapsed time, shown after the copy action. */
  void assistant(LinearLayout parent, String value, boolean streaming, String footer) {
    assistant(parent, value, streaming, footer, null);
  }
  void assistant(LinearLayout parent, String value, boolean streaming, String footer, Runnable quote) {
    LinearLayout block = Ui.col(activity);
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
    space.topMargin = dp(10);
    space.bottomMargin = dp(6);
    parent.addView(block, space);

    LinearLayout meta = Ui.row(activity);
    View dot = new View(activity);
    dot.setBackground(Ui.round(Ui.mint, dp(3)));
    meta.addView(dot, Ui.w(dp(5), dp(5)));
    ((LinearLayout.LayoutParams) dot.getLayoutParams()).setMarginEnd(dp(6));
    TextView source = Ui.text(activity, engineName, 11.5f, Ui.faint, Ui.medium(activity));
    source.setSingleLine(true);
    source.setEllipsize(TextUtils.TruncateAt.END);
    meta.addView(source, new LinearLayout.LayoutParams(0, -2, 1f));
    if (streaming) {
      TextView live = Ui.text(activity, "生成中", 11.5f, Ui.accent, null);
      live.setAlpha(0.85f);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.leftMargin = dp(6);
      meta.addView(live, lp);
      Ui.ellipsis(live, "生成中");
    }
    LinearLayout.LayoutParams metaSpace = new LinearLayout.LayoutParams(-1, -2);
    metaSpace.bottomMargin = dp(7);
    block.addView(meta, metaSpace);

    int offset = 0;
    Matcher fence = FENCE.matcher(value);
    while (fence.find(offset)) {
      prose(block, value.substring(offset, fence.start()));
      String delimiter = fence.group(1);
      Pattern closing = Pattern.compile(
          "(?m)^" + Pattern.quote(delimiter) + (delimiter.charAt(0) == '`' ? "`*" : "~*") + "[ \\t]*(?:\\n|$)");
      Matcher end = closing.matcher(value);
      int codeEnd = value.length(), next = value.length();
      if (end.find(fence.end())) {
        codeEnd = end.start();
        next = end.end();
      }
      block.addView(codeBlock(fence.group(2).trim(), value.substring(fence.end(), codeEnd).replaceFirst("\\n$", "")));
      offset = next;
      if (offset >= value.length()) break;
    }
    if (offset < value.length()) prose(block, value.substring(offset));

    if (streaming) {
      TextView caret = Ui.text(activity, "▍", 14, Ui.accent, null);
      caret.setAlpha(0.9f);
      Ui.pulse(caret);
      block.addView(caret);
    }
    LinearLayout actions = Ui.row(activity);
    actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
    actions.addView(ghost("复制", R.drawable.ic_copy, () -> copy(value)));
    if (quote != null) actions.addView(ghost("引用", R.drawable.ic_quote, quote));
    if (!footer.isEmpty()) {
      TextView summary = Ui.text(activity, footer, 11.5f, Ui.faint, null);
      LinearLayout.LayoutParams summarySpace = new LinearLayout.LayoutParams(-2, -2);
      summarySpace.leftMargin = dp(10);
      actions.addView(summary, summarySpace);
    }
    Ui.margin(actions, 0, 2, 0, 0);
    block.addView(actions);
  }

  /** 取命令或参数的第一行，作为卡片标题下方的灰色预览。 */
  private static String commandPreview(String kind, String value) {
    if (value == null) return "";
    String text = value.trim();
    if (text.isEmpty()) return "";
    int cut = text.indexOf('\n');
    if (cut > 0) text = text.substring(0, cut);
    text = text.replaceAll("\\s+", " ").trim();
    if (text.length() > 120) text = text.substring(0, 120) + "…";
    if (text.startsWith("{") || text.startsWith("[")) return "";
    return text;
  }

  private void prose(LinearLayout parent, String value) {
    if (value.trim().isEmpty()) return;
    TextView body = text("", 15, Ui.text);
    body.setLineSpacing(dp(5), 1f);
    markdown.setMarkdown(body, value.trim());
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(2);
    parent.addView(body, lp);
  }

  /** Code card: language label, copy control, horizontally scrolling mono body. */
  View codeBlock(String language, String value) {
    LinearLayout card = Ui.col(activity);
    card.setBackground(Ui.round(activity, Ui.cardHigh, 14, 0.8f, Ui.lineSoft));
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
    space.topMargin = dp(8);
    space.bottomMargin = dp(8);
    card.setLayoutParams(space);

    LinearLayout header = Ui.row(activity);
    Ui.pad(header, 12, 8, 6, 4);
    TextView label = Ui.text(activity, language.isEmpty() ? "代码" : language, 11, Ui.faint, Ui.medium(activity));
    label.setLetterSpacing(0.06f);
    label.setTextIsSelectable(false);
    label.setSingleLine(true);
    label.setEllipsize(TextUtils.TruncateAt.END);
    header.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
    header.addView(ghost("复制", R.drawable.ic_copy, () -> copy(value)));
    card.addView(header);

    HorizontalScrollView scroll = new HorizontalScrollView(activity);
    scroll.setFillViewport(true);
    scroll.setHorizontalScrollBarEnabled(false);
    TextView body = text(value, 12.5f, Ui.text);
    body.setTypeface(Ui.mono(activity));
    body.setLineSpacing(dp(3), 1f);
    body.setHorizontallyScrolling(true);
    body.setPadding(dp(12), 0, dp(14), dp(12));
    scroll.addView(body, new FrameLayout.LayoutParams(-2, -2));
    card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    return card;
  }

  /**
   * 子代理卡片：把同一子任务的连续事件收成一张卡。折叠时仍显示模型、工具数和
   * 最近一条输出，展开后按时间顺序列出该子任务的命令、思考和回复。
   */
  void subagent(LinearLayout parent, String title, String meta, String status, int statusColor,
                boolean active, String preview, java.util.List<String[]> rows, boolean open, Runnable onToggle) {
    LinearLayout card = Ui.col(activity);
    card.setBackground(Ui.round(activity, Ui.card, 14, 0.9f, Ui.lineSoft));
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
    space.topMargin = dp(2);
    space.bottomMargin = dp(7);
    parent.addView(card, space);

    LinearLayout header = Ui.row(activity);
    Ui.pad(header, 12, 9, 10, 8);
    FrameLayout avatar = new FrameLayout(activity);
    avatar.setBackground(Ui.round(Ui.accentSoft, dp(9)));
    ImageView glyph = new ImageView(activity);
    glyph.setImageResource(R.drawable.ic_spark);
    glyph.setColorFilter(Ui.accent);
    FrameLayout.LayoutParams glyphSpace = new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER);
    avatar.addView(glyph, glyphSpace);
    LinearLayout.LayoutParams avatarSpace = new LinearLayout.LayoutParams(dp(26), dp(26));
    avatarSpace.rightMargin = dp(9);
    header.addView(avatar, avatarSpace);
    TextView name = Ui.text(activity, title, 12.5f, active ? Ui.accent : Ui.text, Ui.medium(activity));
    name.setTextIsSelectable(false);
    name.setSingleLine(true);
    name.setEllipsize(TextUtils.TruncateAt.END);
    header.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
    TextView pill = Ui.pill(activity, status, statusColor, Ui.cardHigh);
    pill.setTextSize(10f);
    header.addView(pill);
    ImageView chevron = new ImageView(activity);
    chevron.setImageResource(open ? R.drawable.ic_expand : R.drawable.ic_chevron);
    chevron.setColorFilter(Ui.faint);
    LinearLayout.LayoutParams chevronSpace = new LinearLayout.LayoutParams(dp(16), dp(16));
    chevronSpace.leftMargin = dp(6);
    header.addView(chevron, chevronSpace);
    card.addView(header);

    if (!meta.isEmpty()) {
      TextView line = Ui.text(activity, meta, 10.5f, Ui.faint, null);
      line.setSingleLine(true);
      line.setEllipsize(TextUtils.TruncateAt.END);
      line.setPadding(dp(47), 0, dp(12), 7);
      card.addView(line);
    }
    if (!preview.isEmpty()) {
      TextView line = Ui.text(activity, preview, 11.5f, Ui.muted, null);
      line.setMaxLines(open ? 3 : 2);
      line.setEllipsize(TextUtils.TruncateAt.END);
      line.setLineSpacing(dp(2), 1f);
      line.setPadding(dp(47), 0, dp(12), 9);
      card.addView(line);
    }
    LinearLayout detail = Ui.col(activity);
    detail.setPadding(dp(47), 0, dp(12), 2);
    detail.setVisibility(open ? View.VISIBLE : View.GONE);
    card.addView(detail);
    final boolean[] filled = {open};
    if (open) subagentRows(detail, rows);

    LinearLayout actions = Ui.row(activity);
    Ui.pad(actions, 6, 0, 6, 2);
    LinearLayout footer = ghost(open ? "收起过程" : "展开过程 · " + rows.size(), open ? R.drawable.ic_expand : R.drawable.ic_chevron, null);
    TextView footerLabel = (TextView) footer.getChildAt(footer.getChildCount() - 1);
    actions.addView(footer);
    card.addView(actions);

    Runnable toggle = () -> {
      boolean opening = detail.getVisibility() != View.VISIBLE;
      if (opening && !filled[0]) {
        subagentRows(detail, rows);
        filled[0] = true;
      }
      detail.setVisibility(opening ? View.VISIBLE : View.GONE);
      chevron.setImageResource(opening ? R.drawable.ic_expand : R.drawable.ic_chevron);
      footerLabel.setText(opening ? "收起过程" : "展开过程 · " + rows.size());
      if (opening) Ui.enter(detail, 0);
      onToggle.run();
    };
    Ui.touchable(footer);
    footer.setOnClickListener(v -> toggle.run());
    Ui.touchable(header);
    Ui.ripple(header, Ui.text);
    header.setOnClickListener(v -> toggle.run());
  }

  private void subagentRows(LinearLayout detail, java.util.List<String[]> rows) {
    for (String[] row : rows) {
      TextView label = Ui.text(activity, row[0], 10.5f, Ui.muted, Ui.medium(activity));
      label.setSingleLine(true);
      label.setEllipsize(TextUtils.TruncateAt.END);
      detail.addView(label);
      if (row.length > 1 && row[1] != null && !row[1].trim().isEmpty()) {
        TextView value = text(row[1], 11.5f, Ui.faint);
        value.setMaxLines(6);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams valueSpace = new LinearLayout.LayoutParams(-1, -2);
        valueSpace.bottomMargin = dp(7);
        detail.addView(value, valueSpace);
      }
    }
  }

  /** Reasoning / command / file change: one calm row that opens into raw detail. */
  void activity(LinearLayout parent, String id, String label, String kind, String value, Set<String> expanded) {
    activity(parent, id, label, kind, value, expanded, "");
  }
  void activity(LinearLayout parent, String id, String label, String kind, String value, Set<String> expanded, String livePreview) {
    activity(parent, id, label, kind, value, expanded, livePreview, null);
  }
  void activity(LinearLayout parent, String id, String label, String kind, String value, Set<String> expanded,
                String livePreview, JSONObject item) {
    LinearLayout card = Ui.col(activity);
    card.setBackground(Ui.round(activity, Ui.card, 14, 0.8f, Ui.lineSoft));
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
    space.topMargin = dp(2);
    space.bottomMargin = dp(7);
    parent.addView(card, space);

    boolean open = kind.equals("fileReport") || expanded.contains(id);
    LinearLayout header = Ui.row(activity);
    Ui.pad(header, 12, 9, 10, 9);
    int icon = kind.equals("commandExecution") ? R.drawable.ic_terminal
        : (kind.equals("fileChange") || kind.equals("turnDiff") || kind.equals("fileReport")) ? R.drawable.ic_file
                                                 : R.drawable.ic_spark;
    ImageView badge = new ImageView(activity);
    badge.setImageResource(icon);
    badge.setColorFilter(Ui.muted);
    LinearLayout.LayoutParams badgeSpace = Ui.w(dp(15), dp(15));
    badgeSpace.rightMargin = dp(9);
    header.addView(badge, badgeSpace);
    boolean working = label.contains("思考中") || label.contains("进行中");
    TextView title = Ui.text(activity, label, 12.5f, working ? Ui.accent : Ui.muted, Ui.medium(activity));
    title.setTextIsSelectable(false);
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
    if (working) {
      badge.setColorFilter(Ui.accent);
      Ui.pulse(badge);
      Ui.ellipsis(title, label);
    }
    ImageView chevron = new ImageView(activity);
    chevron.setImageResource(open ? R.drawable.ic_expand : R.drawable.ic_chevron);
    chevron.setColorFilter(Ui.faint);
    chevron.setAlpha(0.8f);
    header.addView(chevron, Ui.w(dp(16), dp(16)));
    card.addView(header);
    /* 折叠时也能看到具体执行的命令/参数，而不是只有一个「工具」。 */
    String preview = fileReport(kind) ? "" : commandPreview(kind, livePreview.isEmpty() ? value : livePreview);
    if (!preview.isEmpty()) {
      TextView line = Ui.text(activity, preview, 11.5f, Ui.faint, null);
      line.setSingleLine(true);
      line.setEllipsize(TextUtils.TruncateAt.MIDDLE);
      line.setPadding(dp(24), 0, dp(12), dp(9));
      card.addView(line);
    }

    LinearLayout detail = Ui.col(activity);
    LinearLayout.LayoutParams detailSpace = new LinearLayout.LayoutParams(-1, -2);
    detailSpace.setMargins(dp(12), 0, dp(12), dp(4));
    card.addView(detail, detailSpace);
    detail.setVisibility(open ? View.VISIBLE : View.GONE);
    final boolean[] filled = {false};
    if (open) {
      fillActivityDetail(detail, kind, value, item);
      filled[0] = true;
    }
    Ui.touchable(header);
    Ui.rippleRounded(header, 12, Ui.text);
    header.setOnClickListener(v -> {
      boolean opening = detail.getVisibility() != View.VISIBLE;
      if (opening && !filled[0]) {
        fillActivityDetail(detail, kind, value, item);
        filled[0] = true;
      }
      if (opening)
        expanded.add(id);
      else
        expanded.remove(id);
      detail.setVisibility(opening ? View.VISIBLE : View.GONE);
      chevron.setImageResource(opening ? R.drawable.ic_expand : R.drawable.ic_chevron);
      if (opening) Ui.enter(detail, 0);
    });
  }

  private boolean fileReport(String kind) {
    return "fileReport".equals(kind);
  }

  /** Compact +A -R label; zero sides stay implicit, like Codex and Claude diff cells. */
  static String diffStat(int added, int removed) {
    if (added > 0 && removed > 0) return "+" + added + " -" + removed;
    if (added > 0) return "+" + added;
    if (removed > 0) return "-" + removed;
    return "";
  }

  private void fillActivityDetail(LinearLayout detail, String kind, String value, JSONObject item) {
    if (fileReport(kind) && item != null) fileReportDetail(detail, item);
    else detail.addView(codeBlock("", value));
  }

  /** Mirrors Codex's Edited-files cell and Claude's diff list: one row per file, not a raw path dump. */
  private void fileReportDetail(LinearLayout detail, JSONObject report) {
    JSONObject counts = report.optJSONObject("counts");
    int created = counts == null ? 0 : counts.optInt("created", 0);
    int modified = counts == null ? 0 : counts.optInt("modified", 0);
    int deleted = counts == null ? 0 : counts.optInt("deleted", 0);
    int total = created + modified + deleted;
    int added = report.optInt("added", -1);
    int removed = report.optInt("removed", -1);
    JSONArray changes = report.optJSONArray("changes");
    String root = report.optString("root", "");

    LinearLayout summary = Ui.row(activity);
    summary.setGravity(16);
    Ui.pad(summary, 2, 1, 2, 6);
    if (created > 0) summary.addView(filePill("新增 " + created, Ui.mint));
    if (modified > 0) summary.addView(filePill("修改 " + modified, Ui.accent));
    if (deleted > 0) summary.addView(filePill("删除 " + deleted, Ui.danger));
    String totalStat = diffStat(added, removed);
    if (!totalStat.isEmpty()) {
      TextView stat = Ui.text(activity, totalStat, 11.0f, Ui.faint, Ui.mono(activity));
      LinearLayout.LayoutParams statSpace = new LinearLayout.LayoutParams(-2, -2);
      statSpace.leftMargin = dp(4);
      summary.addView(stat, statSpace);
    }
    detail.addView(summary);

    final int limit = 6;
    int shown = 0;
    if (changes != null) {
      for (int i = 0; i < changes.length() && i < limit; i++) {
        JSONObject change = changes.optJSONObject(i);
        if (change == null) continue;
        detail.addView(fileChangeRow(change, root));
        shown++;
      }
    }
    final int firstShown = shown;
    if (changes != null && changes.length() > firstShown) {
      LinearLayout more = ghost("查看全部 " + changes.length() + " 个文件", R.drawable.ic_expand, () -> {});
      detail.addView(more);
      more.setOnClickListener(v -> {
        int index = detail.indexOfChild(more);
        detail.removeView(more);
        for (int i = firstShown; i < changes.length(); i++) {
          JSONObject change = changes.optJSONObject(i);
          if (change != null) detail.addView(fileChangeRow(change, root), index++);
        }
      });
    }
    boolean hasDiff = false;
    if (changes != null) for (int i = 0; i < changes.length(); i++) {
      JSONObject change = changes.optJSONObject(i);
      if (change != null && !change.optString("diff", "").isEmpty()) { hasDiff = true; break; }
    }
    if (hasDiff) {
      LinearLayout review = ghost("查看改动", R.drawable.ic_code, () -> {
        if (activity instanceof NativeHomeActivity) ((NativeHomeActivity) activity).showDiffs(report);
      });
      detail.addView(review);
    }
    if (report.optBoolean("partial", false)) {
      TextView note = Ui.text(activity, "扫描未完成，清单可能不完整", 10.5f, Ui.warn, null);
      Ui.pad(note, 2, 4, 2, 1);
      detail.addView(note);
    }
    if (!root.isEmpty()) {
      TextView scope = Ui.text(activity, "范围：" + root, 10.5f, Ui.faint, null);
      scope.setSingleLine(true);
      scope.setEllipsize(TextUtils.TruncateAt.MIDDLE);
      Ui.pad(scope, 2, 4, 2, 1);
      detail.addView(scope);
    }
    TextView hint = Ui.text(activity, "点文件名打开修改后的内容", 10.5f, Ui.faint, null);
    Ui.pad(hint, 2, 2, 2, 10);
    detail.addView(hint);
  }

  private TextView filePill(String label, int color) {
    TextView pill = Ui.text(activity, label, 10.5f, color, Ui.medium(activity));
    pill.setGravity(17);
    pill.setPadding(dp(7), dp(2), dp(7), dp(2));
    pill.setBackground(Ui.round(Ui.alpha(color, 0.12f), dp(8)));
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
    space.rightMargin = dp(5);
    pill.setLayoutParams(space);
    return pill;
  }

  private View fileChangeRow(JSONObject change, String root) {
    return fileChangeRow(change.optString("path", ""), change.optString("kind", "modified"),
        change.optInt("added", -1), change.optInt("removed", -1), root, "");
  }

  private View fileChangeRow(CollaborationFiles.Change change, String note) {
    return fileChangeRow(change.path, change.kind, change.added, change.removed, change.root, note);
  }

  private View fileChangeRow(String path, String kind, int added, int removed, String root, String note) {
    String tag = kind.equals("created") ? "新" : kind.equals("deleted") ? "删" : "改";
    int color = kind.equals("created") ? Ui.mint : kind.equals("deleted") ? Ui.danger : Ui.accent;
    LinearLayout row = Ui.row(activity);
    row.setGravity(16);
    Ui.pad(row, 2, 0, 2, 0);
    TextView badge = Ui.text(activity, tag, 10.5f, color, Ui.medium(activity));
    badge.setGravity(17);
    badge.setBackground(Ui.round(Ui.alpha(color, 0.12f), dp(8)));
    row.addView(badge, Ui.w(dp(22), dp(20)));

    LinearLayout labelBox = Ui.col(activity);
    LinearLayout labelRow = Ui.row(activity);
    labelRow.setGravity(16);
    Ui.pad(labelRow, 7, 3, 4, note.isEmpty() ? 3 : 0);
    String display = path;
    if (!root.isEmpty() && path.startsWith(root + "/")) display = path.substring(root.length() + 1);
    TextView label = Ui.text(activity, display, 12.0f, Ui.text, null);
    label.setSingleLine(true);
    label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    labelRow.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
    String rowStat = diffStat(added, removed);
    if (!rowStat.isEmpty()) {
      TextView stat = Ui.text(activity, rowStat, 10.5f, Ui.faint, Ui.mono(activity));
      LinearLayout.LayoutParams statSpace = new LinearLayout.LayoutParams(-2, -2);
      statSpace.leftMargin = dp(7);
      labelRow.addView(stat, statSpace);
    }
    ImageView open = new ImageView(activity);
    open.setImageResource(kind.equals("deleted") ? R.drawable.ic_close : R.drawable.ic_chevron);
    open.setColorFilter(Ui.faint);
    LinearLayout.LayoutParams openSpace = Ui.w(dp(13), dp(13));
    openSpace.leftMargin = dp(5);
    labelRow.addView(open, openSpace);
    labelBox.addView(labelRow, Ui.fill());
    if (!note.isEmpty()) {
      TextView who = Ui.text(activity, note, 10.0f, Ui.faint, null);
      who.setSingleLine(true);
      who.setEllipsize(TextUtils.TruncateAt.END);
      Ui.pad(who, 7, 0, 4, 3);
      labelBox.addView(who, Ui.fill());
    }
    row.addView(labelBox, new LinearLayout.LayoutParams(0, -2, 1f));

    if (!kind.equals("deleted")) {
      Ui.touchable(labelRow);
      Ui.rippleRounded(labelRow, 8, Ui.text);
      labelRow.setOnClickListener(v -> {
        String absolute = path;
        if (!new File(path).isAbsolute() && !root.isEmpty()) absolute = new File(root, path).getAbsolutePath();
        if (activity instanceof NativeHomeActivity) ((NativeHomeActivity) activity).openEditor(absolute);
      });
    }
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
    space.topMargin = dp(1);
    row.setLayoutParams(space);
    return row;
  }

  /** Final collaboration summary: one section per member plus an explicit shared section. */
  void collaborationFiles(LinearLayout parent, JSONArray reports, String status) {
    CollaborationFiles.Report report = CollaborationFiles.of(reports);
    if (report.total == 0) return;

    LinearLayout card = Ui.col(activity);
    card.setBackground(Ui.round(activity, Ui.card, 16, 0.8f, Ui.lineSoft));
    card.setPadding(dp(12), dp(10), dp(12), dp(8));
    LinearLayout.LayoutParams space = Ui.fill();
    space.topMargin = dp(10);
    space.bottomMargin = dp(4);
    parent.addView(card, space);

    LinearLayout titleRow = Ui.row(activity);
    titleRow.setGravity(16);
    TextView title = Ui.text(activity, "协作文件改动", 13.0f, Ui.text, Ui.medium(activity));
    titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
    boolean ok = status.equals("completed") || status.equals("awaiting_acceptance") || status.equals("succeeded");
    int color = ok ? Ui.mint : status.equals("cancelled") || status.equals("interrupted") ? Ui.warn : Ui.danger;
    TextView state = Ui.pill(activity, ConversationCollaboration.state(status), color, Ui.alpha(color, 0.12f));
    state.setTextSize(10.0f);
    titleRow.addView(state);
    card.addView(titleRow);

    StringBuilder summary = new StringBuilder();
    summary.append(report.members.size()).append(" 位成员 · ").append(report.total).append(" 个文件");
    String stats = diffStat(report.added, report.removed);
    if (!stats.isEmpty()) summary.append(" · ").append(stats);
    TextView line = Ui.text(activity, summary.toString(), 11.5f, Ui.muted, null);
    Ui.pad(line, 2, 3, 2, 4);
    card.addView(line);

    if (!ok) {
      TextView warn = Ui.text(activity, "项目未正常完成，以下为已报告的文件", 10.5f, Ui.warn, null);
      Ui.pad(warn, 2, 0, 2, 4);
      card.addView(warn);
    }

    for (CollaborationFiles.Member member : report.members) {
      LinearLayout memberHead = memberHeader(member);
      card.addView(memberHead);
      if (member.files.isEmpty()) {
        TextView none = Ui.text(activity, "没有单独报告的文件", 10.5f, Ui.faint, null);
        Ui.pad(none, 2, 0, 2, 2);
        card.addView(none);
      } else {
        for (CollaborationFiles.Change change : member.files) card.addView(fileChangeRow(change, ""));
      }
    }

    if (!report.shared.isEmpty()) {
      LinearLayout sharedHead = Ui.row(activity);
      sharedHead.setGravity(16);
      Ui.pad(sharedHead, 2, 10, 2, 2);
      TextView label = Ui.text(activity, "共同报告", 11.5f, Ui.warn, Ui.medium(activity));
      sharedHead.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
      TextView count = Ui.text(activity, report.shared.size() + " 个文件", 10.5f, Ui.faint, null);
      sharedHead.addView(count);
      card.addView(sharedHead);
      TextView hint = Ui.text(activity, "同一文件被多个成员报告，无法可靠区分写入者，因此不归给单人。", 10.5f, Ui.faint, null);
      hint.setLineSpacing(dp(2), 1f);
      Ui.pad(hint, 2, 0, 2, 3);
      card.addView(hint);
      for (CollaborationFiles.Change change : report.shared) {
        StringBuilder who = new StringBuilder("报告成员：");
        int i = 0;
        for (String name : change.memberNames) {
          if (i++ > 0) who.append("、");
          who.append(name);
        }
        card.addView(fileChangeRow(change, who.toString()));
      }
    }

    TextView foot = Ui.text(activity, "执行完成后汇总；成员归属来自各自的文件报告。", 10.0f, Ui.faint, null);
    Ui.pad(foot, 2, 6, 2, 2);
    card.addView(foot);
  }

  private LinearLayout memberHeader(CollaborationFiles.Member member) {
    LinearLayout row = Ui.row(activity);
    row.setGravity(16);
    Ui.pad(row, 2, 9, 2, 2);
    LinearLayout labels = Ui.col(activity);
    TextView name = Ui.text(activity, member.name, 12.5f, Ui.accent, Ui.medium(activity));
    name.setSingleLine(true);
    name.setEllipsize(TextUtils.TruncateAt.END);
    labels.addView(name, Ui.fill());
    StringBuilder meta = new StringBuilder();
    if (!member.engine.isEmpty()) meta.append(NativeUi.engineName(member.engine));
    if (!member.model.isEmpty()) {
      if (meta.length() > 0) meta.append(" · ");
      meta.append(NativeHomeActivity.shortModel(member.model));
    }
    if (meta.length() > 0) {
      TextView detail = Ui.text(activity, meta.toString(), 10.0f, Ui.faint, null);
      detail.setSingleLine(true);
      labels.addView(detail, Ui.fill());
    }
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
    String stat = diffStat(member.added, member.removed);
    if (!stat.isEmpty()) {
      TextView value = Ui.text(activity, stat, 10.5f, Ui.faint, Ui.mono(activity));
      row.addView(value);
    }
    return row;
  }

}
