package app.aixs.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import io.noties.markwon.Markwon;
import java.util.Set;
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

  /** User message: right-aligned bubble that never spans the full column. */
  void user(LinearLayout parent, String value) {
    user(parent, value, null, null);
  }
  void user(LinearLayout parent, String value, Runnable edit, Runnable quote) {
    LinearLayout row = Ui.row(activity);
    row.setGravity(Gravity.END);
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
    space.topMargin = dp(14);
    space.bottomMargin = dp(4);
    parent.addView(row, space);
    TextView body = text(value, 15, Ui.onAccentSoft);
    int width = activity.getResources().getDisplayMetrics().widthPixels;
    body.setMaxWidth((int) (width * 0.78f));
    body.setPadding(dp(14), dp(11), dp(14), dp(11));
    body.setBackground(Ui.corners(Ui.accentSoft, dp(18), dp(18), dp(5), dp(18)));
    row.addView(body, new LinearLayout.LayoutParams(-2, -2));
    LinearLayout actions = Ui.row(activity);
    actions.setGravity(Gravity.END);
    actions.addView(ghost("复制", R.drawable.ic_copy, () -> copy(value)));
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
    meta.addView(Ui.text(activity, engineName, 11.5f, Ui.faint, Ui.medium(activity)));
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

  /** Reasoning / command / file change: one calm row that opens into raw detail. */
  void activity(LinearLayout parent, String id, String label, String kind, String value, Set<String> expanded) {
    LinearLayout card = Ui.col(activity);
    card.setBackground(Ui.round(activity, Ui.card, 14, 0.8f, Ui.lineSoft));
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
    space.topMargin = dp(2);
    space.bottomMargin = dp(7);
    parent.addView(card, space);

    boolean open = expanded.contains(id);
    LinearLayout header = Ui.row(activity);
    Ui.pad(header, 12, 9, 10, 9);
    int icon = kind.equals("commandExecution") ? R.drawable.ic_terminal
        : kind.equals("fileChange")              ? R.drawable.ic_file
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
    String preview = commandPreview(kind, value);
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
      detail.addView(codeBlock("", value));
      filled[0] = true;
    }
    Ui.touchable(header);
    Ui.ripple(header, Ui.text);
    header.setOnClickListener(v -> {
      boolean opening = detail.getVisibility() != View.VISIBLE;
      if (opening && !filled[0]) {
        detail.addView(codeBlock("", value));
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
}
