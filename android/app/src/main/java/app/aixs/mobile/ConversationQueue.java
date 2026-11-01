package app.aixs.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Durable follow-up queue shown above the composer and in a full queue sheet. */
final class ConversationQueue {
  interface Host {
    JSONObject thread();
    /** Queue-only updates must not rebuild the conversation page. */
    void queue(JSONObject thread);
    boolean composerEmpty();
    void restore(JSONArray input);
  }

  private final Activity activity;
  private final Host host;
  private LinearLayout panel;
  private LinearLayout fullList;
  private TextView fullSubtitle;
  private TextView fullControlNote;
  private TextView fullPause;
  private BottomSheetDialog fullSheet;
  private String signature = "";

  ConversationQueue(Activity activity, Host host) {
    this.activity = activity;
    this.host = host;
  }

  void attach(LinearLayout panel) {
    this.panel = panel;
    signature = "";
    render();
  }

  private String id() {
    return host.thread() == null ? "" : host.thread().optString("id");
  }

  private JSONArray visible() {
    JSONArray result = new JSONArray();
    JSONArray all = host.thread() == null ? null : host.thread().optJSONArray("messageQueue");
    for (int i = 0; all != null && i < all.length(); i++) {
      JSONObject entry = all.optJSONObject(i);
      if (entry != null && pending(entry.optString("status"))) result.put(entry);
    }
    return result;
  }

  private static boolean pending(String state) {
    return !state.equals("sent") && !state.equals("included") && !state.equals("deleted");
  }

  private boolean paused() {
    return host.thread() != null && host.thread().optBoolean("messageQueuePaused");
  }

  private boolean active() {
    JSONArray turns = host.thread() == null ? null : host.thread().optJSONArray("turns");
    for (int i = 0; turns != null && i < turns.length(); i++) {
      JSONObject turn = turns.optJSONObject(i);
      if (turn != null && ((turn.optString("status").equals("inProgress") && !turn.optBoolean("queued"))
          || turn.optInt("collaborationActive") > 0)) return true;
    }
    return false;
  }

  void refresh() {
    String owner = id();
    if (owner.isEmpty() || !NativeEngine.ready()) return;
    NativeEngine.call("native/chat/queue/list", NativeUi.json("threadId", owner), r -> {
      if (owner.equals(id()) && !activity.isDestroyed()) host.queue(r.optJSONObject("thread"));
    }, e -> {});
  }

  private void rpc(String action, JSONObject args) {
    rpc(action, args, null);
  }

  private void rpc(String action, JSONObject args, Runnable after) {
    String owner = id();
    try {
      args.put("threadId", owner);
    } catch (JSONException ignored) {
    }
    NativeEngine.call("native/chat/queue/" + action, args, r -> {
      if (owner.equals(id()) && !activity.isDestroyed()) {
        host.queue(r.optJSONObject("thread"));
        if (action.equals("include")) NativeUi.toast(activity, "已提交引用，等待回执");
        if (after != null) after.run();
      }
    }, e -> {
      if (owner.equals(id()) && !activity.isDestroyed()) {
        NativeUi.error(activity, e);
        refresh();
      }
    });
  }

  void render() {
    if (panel == null) return;
    JSONArray entries = visible();
    String next = id() + entries + paused() + active();
    if (next.equals(signature)) return;
    signature = next;

    boolean suppress = android.os.Build.VERSION.SDK_INT >= 29;
    if (suppress) panel.suppressLayout(true);
    try {
      if (entries.length() == 0) {
        panel.setVisibility(View.GONE);
        panel.removeAllViews();
        if (fullSheet != null && fullSheet.isShowing()) fullSheet.dismiss();
        return;
      }
      panel.setVisibility(View.VISIBLE);
      panel.removeAllViews();
      LinearLayout.LayoutParams panelSpace = new LinearLayout.LayoutParams(-1, -2);
      panelSpace.bottomMargin = Ui.dp(activity, 8);
      panel.addView(inlineCard(entries), panelSpace);
      refreshFull(entries);
    } finally {
      if (suppress) panel.suppressLayout(false);
    }
  }

  // ---- compact strip above the composer -----------------------------------

  private LinearLayout inlineCard(JSONArray entries) {
    LinearLayout card = Ui.col(activity);
    card.setBackground(Ui.round(activity, Ui.card, 14, 0.8f, Ui.lineSoft));
    Ui.pad(card, 10, 6, 10, 6);

    LinearLayout header = Ui.row(activity);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setMinimumHeight(Ui.dp(activity, 28));
    ImageView icon = new ImageView(activity);
    icon.setImageResource(R.drawable.ic_queue);
    icon.setColorFilter(Ui.accent);
    header.addView(icon, new LinearLayout.LayoutParams(Ui.dp(activity, 15), Ui.dp(activity, 15)));
    TextView title = Ui.text(activity, "待发 " + entries.length(), 11.5f, Ui.text, Ui.medium(activity));
    LinearLayout.LayoutParams titleSpace = new LinearLayout.LayoutParams(0, -2, 1f);
    titleSpace.leftMargin = Ui.dp(activity, 6);
    header.addView(title, titleSpace);
    if (paused()) {
      TextView pausedLabel = Ui.text(activity, "已暂停", 9.5f, Ui.warn, Ui.medium(activity));
      pausedLabel.setPadding(Ui.dp(activity, 6), Ui.dp(activity, 2), Ui.dp(activity, 6), Ui.dp(activity, 2));
      pausedLabel.setBackground(Ui.round(Ui.alpha(Ui.warn, 0.12f), Ui.dp(activity, 999)));
      header.addView(pausedLabel);
    }
    TextView toggle = textAction(paused() ? "继续" : "暂停", Ui.accent,
        () -> rpc(paused() ? "resume" : "pause", new JSONObject()));
    header.addView(toggle);
    ImageView chevron = new ImageView(activity);
    chevron.setImageResource(R.drawable.ic_chevron);
    chevron.setColorFilter(Ui.faint);
    LinearLayout.LayoutParams chevronSpace = new LinearLayout.LayoutParams(Ui.dp(activity, 12), Ui.dp(activity, 12));
    chevronSpace.leftMargin = Ui.dp(activity, 2);
    header.addView(chevron, chevronSpace);
    header.setContentDescription("打开待发消息，共 " + entries.length() + " 条");
    Ui.touchable(header);
    header.setOnClickListener(v -> open());
    card.addView(header);

    for (int i = 0; i < Math.min(3, entries.length()); i++) {
      LinearLayout.LayoutParams rowSpace = new LinearLayout.LayoutParams(-1, -2);
      rowSpace.topMargin = Ui.dp(activity, 2);
      card.addView(inlineRow(entries.optJSONObject(i)), rowSpace);
    }
    if (entries.length() > 3) {
      TextView more = Ui.text(activity, "查看全部 " + entries.length() + " 条", 10.5f, Ui.accent, Ui.medium(activity));
      Ui.pad(more, 2, 4, 2, 2);
      Ui.touchable(more);
      more.setOnClickListener(v -> open());
      card.addView(more);
    }
    return card;
  }

  /** One flat tag row: message + edit / quote / delete actions. */
  private LinearLayout inlineRow(JSONObject entry) {
    LinearLayout tag = Ui.row(activity);
    tag.setGravity(Gravity.CENTER_VERTICAL);
    tag.setMinimumHeight(Ui.dp(activity, 30));
    tag.setBackground(Ui.round(Ui.cardHigh, Ui.dp(activity, 9)));
    Ui.pad(tag, 6, 2, 3, 2);

    View dot = new View(activity);
    dot.setBackground(Ui.round(stateColor(entry), Ui.dp(activity, 999)));
    tag.addView(dot, new LinearLayout.LayoutParams(Ui.dp(activity, 5), Ui.dp(activity, 5)));

    String preview = bodyText(entry);
    if (preview.isEmpty()) preview = entry.optBoolean("executionResume") ? "继续原任务" : "仅图片附件";
    TextView message = Ui.text(activity, preview, 11.5f, Ui.text, null);
    message.setMaxLines(1);
    message.setEllipsize(TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams messageSpace = new LinearLayout.LayoutParams(0, -2, 1f);
    messageSpace.leftMargin = Ui.dp(activity, 7);
    tag.addView(message, messageSpace);

    String state = entry.optString("status");
    boolean busy = state.equals("dispatching") || state.equals("including");
    boolean resume = entry.optBoolean("executionResume");
    boolean edit = !resume && !busy && !state.equals("needs_reconciliation");
    boolean quote = !resume && state.equals("queued") && active();
    boolean delete = !resume && !busy;
    String meta = stateLabel(entry) + " · " + modeLabel(entry);
    if (imageCount(entry) > 0) meta += " · 附件 " + imageCount(entry);
    tag.setContentDescription(meta + "；" + preview);

    if (!resume) {
      tag.addView(flatAction("编辑", Ui.accent, edit, () -> edit(entry, null)));
      tag.addView(flatAction("引用", Ui.accent, quote, () -> quote(entry)));
      tag.addView(flatAction("删除", Ui.danger, delete, () -> rpc("delete", args(entry))));
    } else {
      TextView special = Ui.text(activity, "等待执行", 10, Ui.faint, null);
      Ui.pad(special, 5, 3, 5, 3);
      tag.addView(special);
    }
    return tag;
  }

  // ---- full queue sheet ---------------------------------------------------

  private void open() {
    if (activity.isFinishing() || activity.isDestroyed()) return;
    if (fullSheet != null && fullSheet.isShowing()) return;
    JSONArray entries = visible();
    if (entries.length() == 0) return;

    final BottomSheetDialog dialog = new BottomSheetDialog(activity);
    LinearLayout root = Ui.col(activity);

    View handle = new View(activity);
    handle.setBackground(Ui.round(Ui.line, Ui.dp(activity, 2)));
    LinearLayout.LayoutParams handleSpace = new LinearLayout.LayoutParams(Ui.dp(activity, 36), Ui.dp(activity, 4));
    handleSpace.gravity = Gravity.CENTER_HORIZONTAL;
    handleSpace.topMargin = Ui.dp(activity, 8);
    handleSpace.bottomMargin = Ui.dp(activity, 3);
    root.addView(handle, handleSpace);

    LinearLayout header = Ui.row(activity);
    header.setGravity(Gravity.TOP);
    Ui.pad(header, 20, 8, 10, 2);
    LinearLayout words = Ui.col(activity);
    TextView heading = Ui.text(activity, "待发消息", 22, Ui.text, Ui.medium(activity));
    heading.setLetterSpacing(-0.02f);
    words.addView(heading);
    fullSubtitle = Ui.text(activity, "", 13, Ui.muted, null);
    fullSubtitle.setLineSpacing(Ui.dp(activity, 4), 1f);
    LinearLayout.LayoutParams subtitleSpace = new LinearLayout.LayoutParams(-1, -2);
    subtitleSpace.topMargin = Ui.dp(activity, 6);
    words.addView(fullSubtitle, subtitleSpace);
    header.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
    header.addView(Ui.iconButton(activity, R.drawable.ic_close, "关闭", dialog::dismiss));
    root.addView(header);

    ScrollView scroll = new ScrollView(activity);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout body = Ui.col(activity);
    Ui.pad(body, 20, 6, 20, 18);
    scroll.addView(body);

    TextView legend = Ui.text(activity,
        "编辑会取消排队并放回输入框；引用会立即交给当前执行，无需确认。",
        11.5f, Ui.muted, null);
    legend.setLineSpacing(Ui.dp(activity, 3), 1f);
    legend.setBackground(Ui.round(Ui.cardHigh, Ui.dp(activity, 12)));
    Ui.pad(legend, 12, 10, 12, 10);
    LinearLayout.LayoutParams legendSpace = new LinearLayout.LayoutParams(-1, -2);
    legendSpace.bottomMargin = Ui.dp(activity, 10);
    body.addView(legend, legendSpace);

    LinearLayout controls = Ui.row(activity);
    controls.setGravity(Gravity.CENTER_VERTICAL);
    fullControlNote = Ui.text(activity, paused() ? "自动发送已暂停" : "按顺序自动接力", 11.5f,
        paused() ? Ui.warn : Ui.muted, Ui.medium(activity));
    controls.addView(fullControlNote, new LinearLayout.LayoutParams(0, -2, 1f));
    fullPause = textAction(paused() ? "继续发送" : "暂停队列", Ui.accent,
        () -> rpc(paused() ? "resume" : "pause", new JSONObject()));
    controls.addView(fullPause);
    LinearLayout.LayoutParams controlsSpace = new LinearLayout.LayoutParams(-1, -2);
    controlsSpace.bottomMargin = Ui.dp(activity, 8);
    body.addView(controls, controlsSpace);

    fullList = Ui.col(activity);
    body.addView(fullList, new LinearLayout.LayoutParams(-1, -2));
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

    LinearLayout footer = Ui.col(activity);
    footer.addView(Ui.divider(activity, 0));
    TextView hint = Ui.text(activity, "引用以成员实际回执和输出为准；不会把控制接收当作任务完成。", 11, Ui.faint, null);
    hint.setLineSpacing(Ui.dp(activity, 3), 1f);
    Ui.pad(hint, 20, 9, 20, 16);
    footer.addView(hint);
    root.addView(footer);

    fullSheet = dialog;
    dialog.setOnDismissListener(d -> {
      if (fullSheet == dialog) {
        fullSheet = null;
        fullList = null;
        fullSubtitle = null;
        fullControlNote = null;
        fullPause = null;
      }
    });
    dialog.setContentView(root);
    dialog.getBehavior().setSkipCollapsed(true);
    dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
    dialog.show();
    View design = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
    if (design != null) {
      float ratio = Math.min(0.82f, 0.48f + entries.length() * 0.06f);
      design.getLayoutParams().height = (int) (activity.getResources().getDisplayMetrics().heightPixels * ratio);
      design.requestLayout();
    }
    refreshFull(entries);
  }

  private void refreshFull(JSONArray entries) {
    if (fullList == null) return;
    fullList.removeAllViews();
    for (int i = 0; i < entries.length(); i++) {
      LinearLayout card = fullCard(entries.optJSONObject(i), i);
      LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
      space.bottomMargin = Ui.dp(activity, 9);
      fullList.addView(card, space);
    }
    if (fullSubtitle != null) {
      fullSubtitle.setText("共 " + entries.length() + " 条 · " + (paused() ? "已暂停自动发送" : "当前任务结束后按顺序发送"));
    }
    if (fullControlNote != null) {
      fullControlNote.setText(paused() ? "自动发送已暂停" : "按顺序自动接力");
      fullControlNote.setTextColor(paused() ? Ui.warn : Ui.muted);
    }
    if (fullPause != null) fullPause.setText(paused() ? "继续发送" : "暂停队列");
  }

  private LinearLayout fullCard(JSONObject entry, int index) {
    LinearLayout card = Ui.col(activity);
    card.setBackground(Ui.round(activity, Ui.card, 16, 0.8f, Ui.lineSoft));
    Ui.pad(card, 14, 12, 14, 10);

    LinearLayout head = Ui.row(activity);
    head.setGravity(Gravity.CENTER_VERTICAL);
    TextView order = Ui.text(activity, "#" + (index + 1), 10.5f, Ui.faint, Ui.mono(activity));
    order.setMinWidth(Ui.dp(activity, 25));
    head.addView(order);
    head.addView(statePill(entry));
    TextView mode = Ui.text(activity, modeLabel(entry), 11, Ui.muted, Ui.medium(activity));
    LinearLayout.LayoutParams modeSpace = new LinearLayout.LayoutParams(-2, -2);
    modeSpace.leftMargin = Ui.dp(activity, 8);
    head.addView(mode, modeSpace);
    card.addView(head);

    String body = bodyText(entry);
    if (!body.isEmpty()) {
      TextView message = Ui.text(activity, body, 13.5f, Ui.text, null);
      message.setMaxLines(5);
      message.setEllipsize(TextUtils.TruncateAt.END);
      message.setLineSpacing(Ui.dp(activity, 4), 1f);
      LinearLayout.LayoutParams messageSpace = new LinearLayout.LayoutParams(-1, -2);
      messageSpace.topMargin = Ui.dp(activity, 8);
      card.addView(message, messageSpace);
    }
    int images = imageCount(entry);
    if (images > 0) {
      TextView attach = Ui.text(activity, "图片附件 " + images + " 张", 11, Ui.muted, null);
      LinearLayout.LayoutParams attachSpace = new LinearLayout.LayoutParams(-1, -2);
      attachSpace.topMargin = Ui.dp(activity, 6);
      card.addView(attach, attachSpace);
    }
    if (!entry.optString("waitReason").isEmpty()) {
      TextView wait = Ui.text(activity, entry.optString("waitReason"), 11.5f, Ui.muted, null);
      wait.setLineSpacing(Ui.dp(activity, 3), 1f);
      LinearLayout.LayoutParams waitSpace = new LinearLayout.LayoutParams(-1, -2);
      waitSpace.topMargin = Ui.dp(activity, 7);
      card.addView(wait, waitSpace);
    }
    if (!entry.optString("error").isEmpty()) {
      TextView error = Ui.text(activity, entry.optString("error"), 11.5f, Ui.danger, null);
      error.setLineSpacing(Ui.dp(activity, 3), 1f);
      LinearLayout.LayoutParams errorSpace = new LinearLayout.LayoutParams(-1, -2);
      errorSpace.topMargin = Ui.dp(activity, 7);
      card.addView(error, errorSpace);
    }

    String state = entry.optString("status");
    boolean busy = state.equals("dispatching") || state.equals("including");
    if (busy) {
      TextView working = Ui.text(activity, "正在处理，完成后会自动更新", 11, Ui.muted, null);
      LinearLayout.LayoutParams workingSpace = new LinearLayout.LayoutParams(-1, -2);
      workingSpace.topMargin = Ui.dp(activity, 8);
      card.addView(working, workingSpace);
    } else {
      LinearLayout actions = Ui.row(activity);
      actions.setGravity(Gravity.CENTER_VERTICAL);
      boolean edit = !entry.optBoolean("executionResume") && !state.equals("needs_reconciliation");
      if (edit) {
        TextView editView = chip("编辑", Ui.text, Color.TRANSPARENT, true,
            () -> edit(entry, this::closeFull));
        actions.addView(editView);
      }
      if (state.equals("queued") && active() && !entry.optBoolean("executionResume")) {
        TextView quote = chip("引用", Ui.onAccentSoft, Ui.accentSoft, false, () -> quote(entry));
        LinearLayout.LayoutParams quoteSpace = new LinearLayout.LayoutParams(-2, -2);
        quoteSpace.leftMargin = Ui.dp(activity, 7);
        actions.addView(quote, quoteSpace);
      }
      if (state.equals("needs_reconciliation") || state.equals("failed")) {
        TextView retry = chip("核对并重试", Ui.warn, Color.TRANSPARENT, true, () -> retry(entry));
        LinearLayout.LayoutParams retrySpace = new LinearLayout.LayoutParams(-2, -2);
        retrySpace.leftMargin = Ui.dp(activity, 7);
        actions.addView(retry, retrySpace);
      }
      if (!entry.optBoolean("executionResume")) {
        TextView delete = chip("删除", Ui.danger, Color.TRANSPARENT, false,
            () -> rpc("delete", args(entry)));
        LinearLayout.LayoutParams deleteSpace = new LinearLayout.LayoutParams(-2, -2);
        deleteSpace.leftMargin = Ui.dp(activity, 7);
        actions.addView(delete, deleteSpace);
      }
      if (actions.getChildCount() > 0) {
        LinearLayout.LayoutParams actionsSpace = new LinearLayout.LayoutParams(-1, -2);
        actionsSpace.topMargin = Ui.dp(activity, 10);
        card.addView(actions, actionsSpace);
      }
    }
    Ui.touchable(card);
    Ui.rippleRounded(card, 16, Ui.text);
    card.setOnClickListener(v -> details(entry));
    return card;
  }

  private void closeFull() {
    if (fullSheet != null) fullSheet.dismiss();
  }

  // ---- actions ------------------------------------------------------------

  /** Editing is local: remove the queue item first, then put its full input back in the composer. */
  private void edit(JSONObject entry, Runnable after) {
    if (!host.composerEmpty()) {
      NativeUi.toast(activity, "输入框已有草稿，请先发送或清空后再编辑待发消息");
      return;
    }
    rpc("delete", args(entry), () -> {
      if (after != null) after.run();
      host.restore(restoreInput(entry));
      NativeUi.toast(activity, "已取消排队，消息已放回输入框");
    });
  }

  private void quote(JSONObject entry) {
    rpc("include", args(entry));
  }

  private void retry(JSONObject entry) {
    new MaterialAlertDialogBuilder(activity).setTitle("重新排队？")
        .setMessage("先核对会话和成员输出，确认这条消息没有被接收，避免重复执行。重试会把完整消息作为下一项任务发送。")
        .setNegativeButton("取消", null).setPositiveButton("确认重新排队", (d, w) -> {
          JSONObject args = args(entry);
          try {
            args.put("confirmed", true);
          } catch (JSONException ignored) {
          }
          rpc("retry", args);
        }).show();
  }

  private void details(JSONObject entry) {
    LinearLayout body = Ui.col(activity);
    Ui.pad(body, 20, 4, 20, 8);
    LinearLayout head = Ui.row(activity);
    head.setGravity(Gravity.CENTER_VERTICAL);
    head.addView(statePill(entry));
    TextView mode = Ui.text(activity, modeLabel(entry), 11.5f, Ui.muted, Ui.medium(activity));
    LinearLayout.LayoutParams modeSpace = new LinearLayout.LayoutParams(-2, -2);
    modeSpace.leftMargin = Ui.dp(activity, 8);
    head.addView(mode, modeSpace);
    body.addView(head);

    String text = bodyText(entry);
    String empty = entry.optBoolean("executionResume") ? "继续原任务" : "这条消息只有图片附件。";
    TextView message = Ui.text(activity, text.isEmpty() ? empty : text, 13.5f, Ui.text, null);
    message.setLineSpacing(Ui.dp(activity, 4), 1f);
    message.setTextIsSelectable(true);
    LinearLayout.LayoutParams messageSpace = new LinearLayout.LayoutParams(-1, -2);
    messageSpace.topMargin = Ui.dp(activity, 12);
    body.addView(message, messageSpace);

    int images = imageCount(entry);
    if (images > 0) {
      TextView attach = Ui.text(activity, "图片附件 " + images + " 张", 11.5f, Ui.muted, null);
      LinearLayout.LayoutParams attachSpace = new LinearLayout.LayoutParams(-1, -2);
      attachSpace.topMargin = Ui.dp(activity, 8);
      body.addView(attach, attachSpace);
    }
    if (!entry.optString("waitReason").isEmpty()) {
      TextView wait = Ui.text(activity, entry.optString("waitReason"), 11.5f, Ui.muted, null);
      wait.setLineSpacing(Ui.dp(activity, 3), 1f);
      LinearLayout.LayoutParams waitSpace = new LinearLayout.LayoutParams(-1, -2);
      waitSpace.topMargin = Ui.dp(activity, 10);
      body.addView(wait, waitSpace);
    }
    if (!entry.optString("error").isEmpty()) {
      TextView error = Ui.text(activity, entry.optString("error"), 11.5f, Ui.danger, null);
      error.setLineSpacing(Ui.dp(activity, 3), 1f);
      LinearLayout.LayoutParams errorSpace = new LinearLayout.LayoutParams(-1, -2);
      errorSpace.topMargin = Ui.dp(activity, 10);
      body.addView(error, errorSpace);
    }
    JSONArray receipts = entry.optJSONArray("receipts");
    if (receipts != null && receipts.length() > 0) {
      TextView label = Ui.text(activity, "执行回执", 12, Ui.muted, Ui.medium(activity));
      LinearLayout.LayoutParams labelSpace = new LinearLayout.LayoutParams(-1, -2);
      labelSpace.topMargin = Ui.dp(activity, 14);
      body.addView(label, labelSpace);
      for (int i = 0; i < receipts.length(); i++) {
        JSONObject receipt = receipts.optJSONObject(i);
        if (receipt == null) continue;
        String state = receipt.optString("status");
        String line = (receipt.optString("mode").equals("collaboration") ? "成员 " + (i + 1) : "当前任务")
            + " · " + NativeUi.engineName(receipt.optString("engine")) + " · "
            + (state.equals("accepted") ? "已接收" : state.equals("unknown") ? "结果待核对" : "未能引入");
        if (!receipt.optString("error").isEmpty()) line += "\n" + receipt.optString("error");
        TextView item = Ui.text(activity, line, 11.5f, state.equals("failed") ? Ui.danger : Ui.muted, null);
        item.setLineSpacing(Ui.dp(activity, 3), 1f);
        LinearLayout.LayoutParams itemSpace = new LinearLayout.LayoutParams(-1, -2);
        itemSpace.topMargin = Ui.dp(activity, 6);
        body.addView(item, itemSpace);
      }
    }

    ScrollView scroll = new ScrollView(activity);
    scroll.addView(body);
    new MaterialAlertDialogBuilder(activity).setTitle("排队消息").setView(scroll)
        .setPositiveButton("关闭", null).show();
  }

  // ---- presentation helpers ----------------------------------------------

  private TextView chip(String label, int fg, int bg, boolean border, Runnable click) {
    TextView view = Ui.text(activity, label, 11.5f, fg, Ui.medium(activity));
    view.setGravity(Gravity.CENTER);
    view.setMinHeight(Ui.dp(activity, 40));
    view.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 8), Ui.dp(activity, 12), Ui.dp(activity, 8));
    view.setBackground(border ? Ui.round(activity, Color.TRANSPARENT, 10, 0.8f, Ui.lineSoft)
        : Ui.round(bg, Ui.dp(activity, 10)));
    Ui.touchable(view);
    Ui.rippleRounded(view, 10, fg);
    view.setOnClickListener(v -> click.run());
    return view;
  }

  private TextView textAction(String label, int color, Runnable click) {
    TextView view = Ui.text(activity, label, 11.5f, color, Ui.medium(activity));
    view.setGravity(Gravity.CENTER);
    view.setMinHeight(Ui.dp(activity, 34));
    Ui.pad(view, 8, 7, 8, 7);
    Ui.touchable(view);
    Ui.rippleRounded(view, 10, color);
    view.setOnClickListener(v -> click.run());
    return view;
  }

  /** Compact text action used inside a flat queue tag. */
  private TextView flatAction(String label, int color, boolean enabled, Runnable click) {
    TextView view = Ui.text(activity, label, 10.5f, enabled ? color : Ui.faint, Ui.medium(activity));
    view.setGravity(Gravity.CENTER);
    view.setMinHeight(Ui.dp(activity, 28));
    Ui.pad(view, 5, 3, 5, 3);
    view.setAlpha(enabled ? 1f : 0.45f);
    if (enabled) {
      Ui.touchable(view);
      Ui.rippleRounded(view, 8, color);
      view.setOnClickListener(v -> click.run());
    } else {
      view.setEnabled(false);
    }
    return view;
  }

  private TextView statePill(JSONObject entry) {
    int color = stateColor(entry);
    TextView pill = Ui.text(activity, stateLabel(entry), 10.5f, color, Ui.medium(activity));
    pill.setGravity(Gravity.CENTER);
    pill.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 4), Ui.dp(activity, 8), Ui.dp(activity, 4));
    pill.setBackground(Ui.round(Ui.alpha(color, 0.12f), Ui.dp(activity, 999)));
    return pill;
  }

  private int stateColor(JSONObject entry) {
    String state = entry.optString("status");
    if (state.equals("failed") || state.equals("needs_reconciliation")) return Ui.danger;
    if (state.equals("dispatching")) return Ui.warn;
    if (state.equals("including")) return Ui.accent;
    return Ui.accent;
  }

  private String stateLabel(JSONObject entry) {
    String state = entry.optString("status");
    if (state.equals("dispatching")) return "发送中";
    if (state.equals("including")) return "引用中";
    if (state.equals("needs_reconciliation")) return "待核对";
    if (state.equals("failed")) return "发送失败";
    return "排队中";
  }

  private String modeLabel(JSONObject entry) {
    if (entry.optBoolean("executionResume")) return "继续原任务";
    return entry.optString("mode").equals("collaboration") ? "协作" : "独立";
  }

  private String bodyText(JSONObject entry) {
    JSONArray input = entry.optJSONArray("input");
    StringBuilder text = new StringBuilder();
    for (int i = 0; input != null && i < input.length(); i++) {
      JSONObject part = input.optJSONObject(i);
      if (part == null || !part.optString("type").equals("text")) continue;
      if (text.length() > 0) text.append('\n');
      text.append(part.optString("text"));
    }
    return text.toString();
  }

  private int imageCount(JSONObject entry) {
    JSONArray input = entry.optJSONArray("input");
    int count = 0;
    for (int i = 0; input != null && i < input.length(); i++) {
      JSONObject part = input.optJSONObject(i);
      if (part != null && !part.optString("type").equals("text")) count++;
    }
    return count;
  }

  private JSONArray restoreInput(JSONObject entry) {
    JSONArray result = new JSONArray();
    JSONArray input = entry.optJSONArray("input");
    for (int i = 0; input != null && i < input.length(); i++) {
      JSONObject part = input.optJSONObject(i);
      if (part != null) result.put(part);
    }
    return result;
  }

  private JSONObject args(JSONObject entry) {
    return NativeUi.json("messageId", entry.optString("id"), "revision", entry.optInt("revision"));
  }
}
