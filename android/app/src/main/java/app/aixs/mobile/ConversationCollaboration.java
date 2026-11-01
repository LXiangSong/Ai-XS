package app.aixs.mobile;

import android.app.Activity;
import android.graphics.Color;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import org.json.*;
import java.util.*;
import java.util.function.Consumer;

/** Controls an execution mode; owns no Activity, navigation or conversation views. */
final class ConversationCollaboration {
  interface Host {
    JSONObject thread(); String engine(); String model(); String effort(); String permission();
    void apply(JSONObject thread); void controls(); void approval(JSONObject request); void approvals(Set<String> valid);
  }
  private static final String[] AGENTS = {"codex", "claude", "deepseek"};
  private static final String[] AGENT_LABELS = {"Codex", "Claude Code", "DeepSeek"};
  private static final String[] API_IDS = {"current", "official", "thirdparty"};
  private static final String[] API_LABELS = {"当前 API 配置", "官方 API", "第三方 API"};
  private static final String[] EFFORT_IDS = {"", "off", "low", "medium", "high", "xhigh", "max"};
  private static final String[] EFFORT_LABELS = {"默认", "关闭", "低", "中", "高", "极高", "最大"};
  private static final String[] PERMISSION_IDS = {"ask", "auto"};
  private static final String[] PERMISSION_LABELS = {"每次询问", "自动执行"};
  private static final java.util.List<String> ROLE_PRESETS =
      Arrays.asList("分析与方案", "实现与验证", "独立复核", "文档整理", "性能与安全");

  private final Activity activity;
  private final Host host;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private JSONArray workflows = new JSONArray();
  private String owner = "", signature = "";
  private boolean visible, reading;
  private final ArrayDeque<JSONObject> pendingEvents=new ArrayDeque<>();
  boolean stream(JSONObject event) {
    if(!id().equals(event.optString("conversationId"))||event.optLong("attemptSequence")<=0)return false;
    try {
      if(CollaborationEvents.turn(host.thread(),event.optString("workflowId"))==null) {
        if(pendingEvents.size()>=256)pendingEvents.removeFirst();pendingEvents.addLast(event);return false;
      }
      return CollaborationEvents.apply(host.thread(),event);
    }catch(JSONException ignored){return false;}
  }
  void replay(JSONObject thread) {
    for(Iterator<JSONObject> it=pendingEvents.iterator();it.hasNext();) {
      JSONObject event=it.next();
      if(thread==null||!thread.optString("id").equals(event.optString("conversationId"))){it.remove();continue;}
      if(CollaborationEvents.turn(thread,event.optString("workflowId"))==null)continue;
      try{CollaborationEvents.apply(thread,event);}catch(JSONException ignored){}it.remove();
    }
  }
  private final Runnable poll = new Runnable() {
    public void run() {
      refresh();
      if (visible) handler.postDelayed(this, 1500);
    }
  };

  ConversationCollaboration(Activity a, Host host) {
    activity = a;
    this.host = host;
  }

  private String id() {
    JSONObject t = host.thread();
    return t == null ? "" : t.optString("id");
  }
  private JSONArray participants() {
    JSONObject t = host.thread();
    JSONArray saved = t == null ? null : t.optJSONArray("collaborationParticipants");
    try {
      return saved == null ? new JSONArray() : new JSONArray(saved.toString());
    } catch (Exception e) {
      return new JSONArray();
    }
  }
  boolean enabled() {
    JSONObject t = host.thread();
    return t != null && t.optString("executionMode").equals("collaboration");
  }
  int members() {
    JSONObject t = host.thread();
    JSONArray p = t == null ? null : t.optJSONArray("collaborationParticipants");
    return p == null ? 0 : p.length();
  }
  boolean working() {
    JSONObject t = host.thread();
    JSONArray turns = t == null ? null : t.optJSONArray("turns");
    for (int i = 0; turns != null && i < turns.length(); i++) {
      JSONObject turn = turns.optJSONObject(i);
      if (turn != null && turn.has("workflowId") &&
          (Arrays.asList("running", "paused", "stopping").contains(turn.optString("collaborationStatus")) ||
           turn.optInt("collaborationActive") > 0))
        return true;
    }
    return false;
  }
  String progress() {
    JSONObject t = host.thread();
    JSONArray turns = t == null ? null : t.optJSONArray("turns");
    for (int i = turns == null ? -1 : turns.length() - 1; i >= 0; i--) {
      JSONObject turn = turns.optJSONObject(i);
      if (turn != null && turn.has("workflowId") &&
          (Arrays.asList("running", "paused", "stopping").contains(turn.optString("collaborationStatus")) ||
           turn.optInt("collaborationActive") > 0))
        return "协作 · " + turn.optInt("collaborationActive") + " 个执行中 · " +
            turn.optInt("collaborationCompleted") + " / " + turn.optInt("collaborationTotal");
    }
    return "";
  }
  boolean owns(String workflow) {
    JSONArray turns = host.thread() == null ? null : host.thread().optJSONArray("turns");
    for (int i = 0; turns != null && i < turns.length(); i++)
      if (workflow.equals(turns.optJSONObject(i).optString("workflowId"))) return true;
    return false;
  }
  void resume() {
    visible = true;
    handler.removeCallbacks(poll);
    handler.post(poll);
  }
  void pause() {
    visible = false;
    handler.removeCallbacks(poll);
  }
  void refresh() {
    String target = id();
    if (target.isEmpty() || !NativeEngine.ready() || reading) return;
    if (!owner.equals(target)) {
      owner = target;
      signature = "";
      workflows = new JSONArray();
    }
    reading = true;
    NativeEngine.call("native/collaboration/session/read", NativeUi.json("threadId", target), r -> {
      reading = false;
      if (!target.equals(id()) || activity.isDestroyed()) return;
      workflows = r.optJSONArray("workflows");
      JSONObject thread = r.optJSONObject("thread");
      if (thread != null && !thread.toString().equals(signature)) {
        signature = thread.toString();
        host.apply(thread);
      }
      Set<String> validApprovals = new HashSet<>();
      for (int i = 0; workflows != null && i < workflows.length(); i++) {
        JSONArray tasks = workflows.optJSONObject(i).optJSONArray("tasks");
        for (int j = 0; tasks != null && j < tasks.length(); j++) {
          JSONArray approvals = tasks.optJSONObject(j).optJSONArray("approvals");
          for (int k = 0; approvals != null && k < approvals.length(); k++) {
            JSONObject approval = approvals.optJSONObject(k);
            validApprovals.add(approval.optString("id"));
            host.approval(approval);
          }
        }
      }
      host.approvals(validApprovals);
    }, e -> { reading = false; });
  }
  private void rpc(String action, JSONObject args, Consumer<JSONObject> done) {
    String target = id();
    NativeEngine.call("native/collaboration/" + action, args, r -> {
      if (!target.equals(id()) || activity.isDestroyed()) return;
      JSONObject t = r.optJSONObject("thread");
      if (t != null) host.apply(t);
      done.accept(r);
      refresh();
    }, e -> NativeUi.error(activity, e));
  }
  private void ensureThread(Runnable next) {
    if (host.thread() != null) {
      next.run();
      return;
    }
    String target = id();
    if (target.isEmpty()) {
      NativeUi.toast(activity, "请先新建或打开一条会话");
      return;
    }
    if (!NativeEngine.ready()) {
      NativeUi.toast(activity, "共享服务尚未启动");
      return;
    }
    NativeEngine.call("native/collaboration/session/read", NativeUi.json("threadId", target), r -> {
      if (activity.isDestroyed()) return;
      JSONObject thread = r.optJSONObject("thread");
      if (thread == null) {
        NativeUi.toast(activity, "会话配置读取失败");
        return;
      }
      host.apply(thread);
      next.run();
    }, e -> NativeUi.error(activity, e));
  }

  // ---- shared sheet chrome ------------------------------------------------
  private static final class Sheet {
    final BottomSheetDialog dialog;
    final ScrollView scroll;
    final LinearLayout body;
    final LinearLayout footer;
    Sheet(BottomSheetDialog dialog, ScrollView scroll, LinearLayout body, LinearLayout footer) {
      this.dialog = dialog;
      this.scroll = scroll;
      this.body = body;
      this.footer = footer;
    }
  }
  private static int dp(Activity a, int value) { return Ui.dp(a, value); }

  /** Full-height modal page with the same heading, sections and footer rhythm as native pages. */
  private static Sheet page(Activity a, String title, String subtitle, double ratio) {
    if (a.isFinishing() || a.isDestroyed()) return null;
    NativeUi.palette(a);
    BottomSheetDialog dialog = new BottomSheetDialog(a);
    LinearLayout root = Ui.col(a);

    View handle = new View(a);
    handle.setBackground(Ui.round(Ui.line, dp(a, 2)));
    LinearLayout.LayoutParams handleSpace = new LinearLayout.LayoutParams(dp(a, 36), dp(a, 4));
    handleSpace.gravity = Gravity.CENTER_HORIZONTAL;
    handleSpace.topMargin = dp(a, 8);
    handleSpace.bottomMargin = dp(a, 3);
    root.addView(handle, handleSpace);

    LinearLayout header = Ui.row(a);
    header.setGravity(Gravity.TOP);
    Ui.pad(header, 20, 8, 10, 2);
    LinearLayout words = Ui.col(a);
    TextView heading = Ui.text(a, title, 22, Ui.text, Ui.medium(a));
    heading.setLetterSpacing(-0.02f);
    words.addView(heading);
    if (subtitle != null && !subtitle.isEmpty()) {
      TextView note = Ui.text(a, subtitle, 13, Ui.muted, null);
      note.setLineSpacing(dp(a, 4), 1f);
      LinearLayout.LayoutParams noteSpace = new LinearLayout.LayoutParams(-1, -2);
      noteSpace.topMargin = dp(a, 6);
      words.addView(note, noteSpace);
    }
    header.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
    header.addView(Ui.iconButton(a, R.drawable.ic_close, "关闭", dialog::dismiss));
    root.addView(header);

    ScrollView scroll = new ScrollView(a);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout body = Ui.col(a);
    Ui.pad(body, 20, 6, 20, 14);
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

    LinearLayout footer = Ui.col(a);
    footer.addView(Ui.divider(a, 0));
    LinearLayout actions = Ui.col(a);
    Ui.pad(actions, 20, 9, 20, 16);
    footer.addView(actions);
    root.addView(footer);

    dialog.setContentView(root);
    dialog.getBehavior().setSkipCollapsed(true);
    dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
    dialog.show();
    if (ratio > 0) {
      View design = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
      if (design != null) {
        design.getLayoutParams().height =
            (int) (a.getResources().getDisplayMetrics().heightPixels * ratio);
        design.requestLayout();
      }
    }
    return new Sheet(dialog, scroll, body, actions);
  }
  private static void section(Activity a, Sheet sheet, String label) {
    NativeUi.section(a, sheet.body, label);
  }
  private static TextView note(Activity a, LinearLayout parent, String value) {
    TextView view = Ui.text(a, value, 12.5f, Ui.muted, null);
    view.setLineSpacing(dp(a, 4), 1f);
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
    space.setMargins(dp(a, 3), dp(a, 2), dp(a, 3), dp(a, 12));
    parent.addView(view, space);
    return view;
  }

  /** Note on a card that has no inner padding of its own. */
  private static TextView cardNote(Activity a, LinearLayout parent, String value) {
    TextView view = Ui.text(a, value, 12.5f, Ui.muted, null);
    view.setLineSpacing(dp(a, 4), 1f);
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
    space.setMargins(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 10));
    parent.addView(view, space);
    return view;
  }

  private EditText formField(LinearLayout parent, String hint, String value, boolean multiline) {
    EditText edit = NativeUi.field(activity, hint, value, multiline);
    if (multiline) {
      edit.setMinLines(3);
      edit.setMaxLines(10);
      edit.setGravity(Gravity.TOP);
    } else {
      edit.setGravity(Gravity.CENTER_VERTICAL);
    }
    NativeUi.addField(activity, parent, edit);
    plainOutline(parent);
    return edit;
  }

  private EditText formNumber(LinearLayout parent, String label, int value) {
    parent.addView(fieldLabel(label));
    EditText edit = numberField("", value);
    NativeUi.addField(activity, parent, edit);
    plainOutline(parent);
    return edit;
  }

  /** The label sits above the box; suppress the floating label that would cross the outline. */
  private static void plainOutline(LinearLayout parent) {
    if (parent.getChildCount() == 0) return;
    View last = parent.getChildAt(parent.getChildCount() - 1);
    if (last instanceof com.google.android.material.textfield.TextInputLayout) {
      com.google.android.material.textfield.TextInputLayout box =
          (com.google.android.material.textfield.TextInputLayout) last;
      box.setHintEnabled(false);
      box.setHint(null);
    }
  }

  private LinearLayout textCard(LinearLayout parent) {
    LinearLayout group = Ui.group(parent);
    Ui.pad(group, 14, 12, 14, 4);
    return group;
  }
  private static TextView footerHint(Activity a, LinearLayout footer, String value) {
    TextView view = Ui.text(a, value, 11.5f, Ui.faint, null);
    view.setGravity(Gravity.CENTER);
    view.setLineSpacing(dp(a, 3), 1f);
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
    space.topMargin = dp(a, 10);
    footer.addView(view, space);
    return view;
  }

  // ---- member list / form primitives -------------------------------------
  private static LinearLayout modeCard(
      Activity a, String title, String detail, boolean selected, Runnable click) {
    LinearLayout card = Ui.row(a);
    card.setGravity(Gravity.CENTER_VERTICAL);
    card.setBackground(Ui.round(a, selected ? Ui.accentSoft : Ui.card, 18,
        selected ? 1f : 0.8f, selected ? Ui.accent : Ui.lineSoft));
    Ui.pad(card, 16, 13, 14, 13);
    LinearLayout labels = Ui.col(a);
    labels.addView(Ui.text(a, title, 15, selected ? Ui.onAccentSoft : Ui.text, Ui.medium(a)));
    TextView caption = Ui.text(a, detail, 12.5f, selected ? Ui.onAccentSoft : Ui.muted, null);
    caption.setLineSpacing(dp(a, 3), 1f);
    LinearLayout.LayoutParams captionSpace = new LinearLayout.LayoutParams(-1, -2);
    captionSpace.topMargin = dp(a, 3);
    labels.addView(caption, captionSpace);
    card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
    if (selected) {
      ImageView check = new ImageView(a);
      check.setImageResource(R.drawable.ic_check);
      check.setColorFilter(Ui.accent);
      card.addView(check, Ui.w(dp(a, 18), dp(a, 18)));
    } else {
      View circle = new View(a);
      circle.setBackground(Ui.round(a, Color.TRANSPARENT, 999, 1.2f, Ui.line));
      card.addView(circle, Ui.w(dp(a, 18), dp(a, 18)));
    }
    Ui.touchable(card);
    Ui.rippleRounded(card, 18, selected ? Ui.onAccentSoft : Ui.text);
    card.setOnClickListener(v -> click.run());
    return card;
  }
  private static LinearLayout choiceRow(
      Activity a, LinearLayout group, String title, String detail, boolean selected, Runnable click) {
    if (group.getChildCount() > 0) group.addView(Ui.divider(a, 16));
    LinearLayout row = Ui.row(a);
    Ui.pad(row, 14, 12, 12, 12);
    row.setMinimumHeight(dp(a, 52));
    if (selected) row.setBackgroundColor(Ui.accentSoft);
    LinearLayout labels = Ui.col(a);
    TextView name = Ui.text(a, title, 14.5f, selected ? Ui.onAccentSoft : Ui.text,
        selected ? Ui.medium(a) : null);
    name.setSingleLine(true);
    name.setEllipsize(TextUtils.TruncateAt.END);
    labels.addView(name);
    if (detail != null && !detail.isEmpty()) {
      TextView sub = Ui.text(a, detail, 12, selected ? Ui.onAccentSoft : Ui.muted, null);
      sub.setMaxLines(2);
      sub.setEllipsize(TextUtils.TruncateAt.END);
      sub.setLineSpacing(dp(a, 2), 1f);
      LinearLayout.LayoutParams subSpace = new LinearLayout.LayoutParams(-1, -2);
      subSpace.topMargin = dp(a, 3);
      labels.addView(sub, subSpace);
    }
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
    if (selected) {
      ImageView check = new ImageView(a);
      check.setImageResource(R.drawable.ic_check);
      check.setColorFilter(Ui.accent);
      row.addView(check, Ui.w(dp(a, 18), dp(a, 18)));
    }
    Ui.touchable(row);
    Ui.rippleRounded(row, 14, Ui.text);
    row.setOnClickListener(v -> click.run());
    group.addView(row);
    return row;
  }
  private static MaterialSwitch switchRow(
      Activity a, LinearLayout group, String title, String detail, boolean checked,
      Consumer<Boolean> onChange) {
    if (group.getChildCount() > 0) group.addView(Ui.divider(a, 16));
    LinearLayout row = Ui.row(a);
    Ui.pad(row, 14, 11, 10, 11);
    row.setMinimumHeight(dp(a, 58));
    LinearLayout labels = Ui.col(a);
    labels.addView(Ui.text(a, title, 14.5f, Ui.text, Ui.medium(a)));
    if (detail != null && !detail.isEmpty()) {
      TextView note = Ui.text(a, detail, 12, Ui.muted, null);
      note.setMaxLines(2);
      note.setEllipsize(TextUtils.TruncateAt.END);
      note.setLineSpacing(dp(a, 2), 1f);
      LinearLayout.LayoutParams noteSpace = new LinearLayout.LayoutParams(-1, -2);
      noteSpace.topMargin = dp(a, 3);
      labels.addView(note, noteSpace);
    }
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
    MaterialSwitch toggle = new MaterialSwitch(a);
    toggle.setChecked(checked);
    toggle.setContentDescription(title);
    row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
    if (onChange != null) toggle.setOnCheckedChangeListener((button, value) -> onChange.accept(value));
    Ui.touchable(row);
    row.setOnClickListener(v -> toggle.toggle());
    group.addView(row);
    return toggle;
  }
  private static int indexOf(String[] values, String value) {
    for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
    return -1;
  }
  private static String engineId(String text) {
    int index = indexOf(AGENT_LABELS, text);
    if (index < 0) index = indexOf(AGENTS, text);
    return index < 0 ? "codex" : AGENTS[index];
  }
  private static String effortLabel(String value) {
    int index = indexOf(EFFORT_IDS, value == null ? "" : value);
    return index < 0 ? "默认" : EFFORT_LABELS[index];
  }
  private static String permissionLabel(String value) {
    int index = indexOf(PERMISSION_IDS, value == null ? "ask" : value);
    return index < 0 ? PERMISSION_LABELS[0] : PERMISSION_LABELS[index];
  }
  private static String apiLabel(String value) {
    int index = indexOf(API_IDS, value == null ? "current" : value);
    return index < 0 ? API_LABELS[0] : API_LABELS[index];
  }
  private static String memberTitle(JSONObject person) {
    String name = person.optString("name");
    return name.isEmpty() ? NativeUi.engineName(person.optString("engine")) : name;
  }
  private static String memberDetail(JSONObject person) {
    String model = person.optString("model").isEmpty() ? "未选择模型" : person.optString("model");
    String role = person.optString("role");
    return NativeUi.engineName(person.optString("engine")) + " · " + model + (role.isEmpty() ? "" : "\n" + role);
  }

  // ---- mode panel ---------------------------------------------------------
  void modes() {
    if (activity.isFinishing()) return;
    if (host.thread() == null) {
      ensureThread(this::modes);
      return;
    }
    final Sheet sheet = page(activity, "协作模式",
        "独立模式由当前 Agent 完成；协作模式按职责分工，可混用不同 Agent 与模型。", 0.86);
    if (sheet == null) return;
    final boolean on = enabled();
    final JSONArray people = participants();
    LinearLayout options = Ui.col(activity);
    addOption(options, modeCard(activity, "独立模式", "当前 Agent 单独执行，不派发协作任务",
        !on, () -> { if (on) setMode("independent", sheet.dialog); }));
    addOption(options, modeCard(activity, "协作模式", members() + " 位成员 · 按职责分工",
        on, () -> {
          if (on) return;
          if (members() < 2) {
            sheet.dialog.dismiss();
            configure(true);
          } else setMode("collaboration", sheet.dialog);
        }));
    sheet.body.addView(options);

    section(activity, sheet, "协作成员");
    if (people.length() == 0) note(activity, sheet.body, "还没有成员。先添加至少 2 位，再启用协作模式。");
    LinearLayout list = Ui.group(sheet.body);
    for (int i = 0; i < people.length(); i++) {
      final int index = i;
      final JSONObject person = people.optJSONObject(i);
      if (person == null) continue;
      Ui.rowItem(list, R.drawable.ic_spark, memberTitle(person), memberDetail(person), () -> {
        if (people.length() < 2) {
          sheet.dialog.dismiss();
          configure(true);
          return;
        }
        editMember(sheet, people, index, enabled() ? "collaboration" : "independent", null);
      });
    }
    Ui.rowItem(list, R.drawable.ic_add, "添加成员",
        people.length() >= 8 ? "最多 8 位成员" : "混用不同 Agent，也可用同一 Agent 的其他模型",
        () -> {
          if (people.length() >= 8) {
            NativeUi.toast(activity, "最多 8 位成员");
            return;
          }
          if (people.length() < 2) {
            sheet.dialog.dismiss();
            configure(true);
            return;
          }
          sheet.dialog.dismiss();
          participant(null, updated -> {
            people.put(updated);
            saveParticipants(people, enabled() ? "collaboration" : "independent", null, "成员配置已更新");
          }, null);
        });
    if (people.length() > 0)
      Ui.rowItem(list, R.drawable.ic_edit, "编辑成员与汇总", "添加、移除、职责与汇总设置",
          () -> { sheet.dialog.dismiss(); configure(false); });

    section(activity, sheet, "资源");
    LinearLayout resources = Ui.group(sheet.body);
    Ui.rowItem(resources, R.drawable.ic_cloud, "并行与资源保护", "全局并发、同 API 并发与低内存保护",
        () -> { sheet.dialog.dismiss(); resources(activity); });

    if (on) {
      sheet.footer.addView(NativeUi.primaryButton(activity, "完成", sheet.dialog::dismiss));
      footerHint(activity, sheet.footer, "编辑成员后，新任务使用新配置；运行中的任务保持原快照。");
    } else {
      sheet.footer.addView(NativeUi.primaryButton(activity,
          people.length() < 2 ? "配置并启用协作" : "启用协作模式", () -> {
            if (people.length() < 2) {
              sheet.dialog.dismiss();
              configure(true);
            } else setMode("collaboration", sheet.dialog);
          }));
      footerHint(activity, sheet.footer, "发送消息才会启动执行；配置对下一轮生效。");
    }
  }
  private static void addOption(LinearLayout parent, LinearLayout card) {
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
    space.bottomMargin = Ui.dp(parent.getContext(), 8);
    parent.addView(card, space);
  }
  private void setMode(String mode, BottomSheetDialog sheet) {
    rpc("session/config", NativeUi.json("threadId", id(), "mode", mode), r -> {
      sheet.dismiss();
      host.controls();
      NativeUi.toast(activity, mode.equals("collaboration") ? "已进入协作模式" : "已回到独立模式");
    });
  }

  // ---- member configuration ----------------------------------------------
  void configure(boolean enable) {
    if (activity.isFinishing()) return;
    JSONObject chat = host.thread();
    if (chat == null) {
      ensureThread(() -> configure(enable));
      return;
    }
    JSONArray saved = chat.optJSONArray("collaborationParticipants");
    JSONArray people;
    try {
      people = saved == null ? new JSONArray() : new JSONArray(saved.toString());
    } catch (Exception e) {
      people = new JSONArray();
    }
    if (people.length() == 0) {
      people.put(defaultMember("成员 1", "分析与方案", host.engine(), host.model(), host.effort(), host.permission()));
      people.put(defaultMember("成员 2", "实现与验证", host.engine(), host.model(), host.effort(), host.permission()));
    }
    configure(enable, people, chat.optBoolean("collaborationSummarize", true));
  }
  private void configure(boolean enable, JSONArray team, boolean summarize) {
    if (activity.isFinishing()) return;
    final Sheet sheet = page(activity, "协作成员",
        "仍使用当前会话和输入框。发送后按成员职责分工；配置对下一轮生效。", 0.9);
    if (sheet == null) return;
    final JSONArray people = team;
    final boolean[] summary = {summarize};
    final String mode = enable ? "collaboration" : (enabled() ? "collaboration" : "independent");

    section(activity, sheet, "成员");
    LinearLayout list = Ui.group(sheet.body);
    for (int i = 0; i < people.length(); i++) {
      final int index = i;
      final JSONObject person = people.optJSONObject(i);
      if (person == null) continue;
      Ui.rowItem(list, R.drawable.ic_spark, memberTitle(person), memberDetail(person), () -> {
        sheet.dialog.dismiss();
        participant(person, updated -> {
          try {
            people.put(index, updated);
          } catch (Exception ignored) {}
          configure(enable, people, summary[0]);
        }, () -> configure(enable, people, summary[0]));
      });
    }
    Ui.rowItem(list, R.drawable.ic_add, "添加成员",
        people.length() >= 8 ? "最多 8 位成员" : "可用同一 Agent 的其他模型",
        () -> {
          if (people.length() >= 8) {
            NativeUi.toast(activity, "最多 8 位成员");
            return;
          }
          sheet.dialog.dismiss();
          participant(null, updated -> {
            people.put(updated);
            configure(enable, people, summary[0]);
          }, () -> configure(enable, people, summary[0]));
        });
    if (people.length() > 2) {
      Button remove = NativeUi.textButton(activity, "移除成员", () -> removeMember(sheet, people, enable, summary));
      remove.setTextColor(Ui.danger);
      LinearLayout.LayoutParams removeSpace = new LinearLayout.LayoutParams(-1, -2);
      removeSpace.bottomMargin = dp(activity, 4);
      sheet.body.addView(remove, removeSpace);
    }

    section(activity, sheet, "汇总");
    LinearLayout summaryGroup = Ui.group(sheet.body);
    switchRow(activity, summaryGroup, "由第一位成员汇总团队成果",
        "第一位成员在其他分工完成后，综合团队结果并给出最终回复。",
        summary[0], value -> summary[0] = value);

    section(activity, sheet, "执行");
    NativeUi.statusCard(activity, sheet.body,
        "保存只影响下一轮执行；正在运行的协作实例继续使用原模型、权限和工具快照。");

    final boolean wasEnabled = enabled();
    Button save = NativeUi.primaryButton(activity, enable ? "启用协作模式" : "保存成员配置", () -> {
      if (people.length() < 2) {
        NativeUi.toast(activity, "协作模式至少需要 2 位成员");
        return;
      }
      for (int i = 0; i < people.length(); i++) {
        JSONObject person = people.optJSONObject(i);
        if (person == null || person.optString("model").trim().isEmpty()) {
          NativeUi.toast(activity, "请为「" + memberTitle(person == null ? new JSONObject() : person) + "」选择模型");
          return;
        }
      }
      rpc("session/config", NativeUi.json("threadId", id(), "mode", mode, "participants", people,
          "summarize", summary[0]), r -> {
            sheet.dialog.dismiss();
            host.controls();
            NativeUi.toast(activity, enable && !wasEnabled ? "已启用协作模式" : "成员配置已保存");
          });
    });
    sheet.footer.addView(save);
    footerHint(activity, sheet.footer, "配置对下一轮生效；发送消息前不会调用模型。");
  }
  private void removeMember(Sheet sheet, JSONArray people, boolean enable, boolean[] summary) {
    if (people.length() <= 2) {
      NativeUi.toast(activity, "至少保留 2 位成员");
      return;
    }
    String[] names = new String[people.length()];
    for (int i = 0; i < people.length(); i++) names[i] = memberTitle(people.optJSONObject(i));
    new MaterialAlertDialogBuilder(activity).setTitle("移除成员").setItems(names, (dialog, index) -> {
      people.remove(index);
      sheet.dialog.dismiss();
      configure(enable, people, summary[0]);
    }).setNegativeButton("取消", null).show();
  }
  private void editMember(Sheet sheet, JSONArray people, int index, String mode, Runnable cancelled) {
    sheet.dialog.dismiss();
    JSONObject person = people.optJSONObject(index);
    participant(person, updated -> {
      try {
        people.put(index, updated);
      } catch (Exception ignored) {}
      saveParticipants(people, mode, null, "成员配置已更新");
    }, cancelled);
  }
  private void saveParticipants(JSONArray team, String mode, Boolean summarize, String message) {
    JSONObject args = NativeUi.json("threadId", id(), "mode", mode, "participants", team);
    try {
      if (summarize != null) args.put("summarize", summarize);
    } catch (Exception ignored) {}
    rpc("session/config", args, r -> {
      host.controls();
      NativeUi.toast(activity, message);
    });
  }
  private JSONObject defaultMember(String name, String role, String engine, String model,
      String effort, String permission) {
    return NativeUi.json("id", UUID.randomUUID().toString(), "name", name, "role", role,
        "engine", engine, "model", model, "effort", effort, "permission", permission,
        "apiMode", "current", "device", false, "browser", false);
  }

  // ---- one member ---------------------------------------------------------
  private void participant(JSONObject original, Consumer<JSONObject> done, Runnable cancelled) {
    if (activity.isFinishing()) return;
    JSONObject initial = original == null
        ? NativeUi.json("id", UUID.randomUUID().toString(), "name", "", "role", "",
              "engine", host.engine(), "model", host.model(), "effort", host.effort(),
              "permission", host.permission(), "apiMode", "current", "device", false, "browser", false)
        : original;
    final Sheet sheet = page(activity, original == null ? "添加成员" : "成员设置",
        "点选每一行调整 Agent、模型、职责与权限；保存只影响下一轮。", 0.9);
    if (sheet == null) return;
    final JSONObject draft;
    try {
      draft = new JSONObject(initial.toString());
    } catch (Exception e) {
      return;
    }
    final boolean[] saved = {false};
    final TextView error = Ui.text(activity, "", 12, Ui.danger, null);
    error.setVisibility(View.GONE);
    error.setLineSpacing(dp(activity, 3), 1f);
    LinearLayout.LayoutParams errorSpace = new LinearLayout.LayoutParams(-1, -2);
    errorSpace.bottomMargin = dp(activity, 2);
    sheet.footer.addView(error, errorSpace);

    final Runnable[] render = new Runnable[1];
    render[0] = () -> {
      int scrollY = sheet.scroll.getScrollY();
      sheet.body.removeAllViews();
      section(activity, sheet, "身份");
      LinearLayout identity = Ui.group(sheet.body);
      settingRow(activity, identity, "成员名称", draft.optString("name"), () ->
          NativeUi.prompt(activity, "成员名称", "例如：方案负责人", draft.optString("name"), false, value -> {
            putQuiet(draft, "name", value.trim());
            render[0].run();
          }));
      settingRow(activity, identity, "职责 / 分工", draft.optString("role"), () ->
          rolePage(draft, render[0]));

      section(activity, sheet, "模型");
      LinearLayout models = Ui.group(sheet.body);
      settingRow(activity, models, "Agent", NativeUi.engineName(draft.optString("engine")), () ->
          chooseAgent(draft, render[0]));
      settingRow(activity, models, "模型", draft.optString("model"), () ->
          chooseModel(draft, render[0]));
      settingRow(activity, models, "API 来源", apiLabel(draft.optString("apiMode")), () ->
          chooseApi(draft, render[0]));

      section(activity, sheet, "行为");
      LinearLayout behavior = Ui.group(sheet.body);
      settingRow(activity, behavior, "思考强度", effortLabel(draft.optString("effort")), () ->
          choicePage("思考强度", "模型不支持所选强度时，会在执行前明确报错。",
              Arrays.asList(EFFORT_LABELS), null, indexOf(EFFORT_IDS, draft.optString("effort")),
              chosen -> {
                if (chosen < 0) return;
                putQuiet(draft, "effort", EFFORT_IDS[chosen]);
                render[0].run();
              }));
      settingRow(activity, behavior, "权限模式", permissionLabel(draft.optString("permission")), () ->
          choicePage("权限模式", "权限沿用该 Agent 的批准机制；自动执行不会跳过 Android 系统授权。",
              Arrays.asList(PERMISSION_LABELS),
              Arrays.asList("执行命令或修改文件前请求批准", "直接执行，不再逐次询问"),
              indexOf(PERMISSION_IDS, draft.optString("permission", "ask")),
              chosen -> {
                if (chosen < 0) return;
                putQuiet(draft, "permission", PERMISSION_IDS[chosen]);
                render[0].run();
              }));

      section(activity, sheet, "工具");
      LinearLayout tools = Ui.group(sheet.body);
      switchRow(activity, tools, "手机操作工具",
          "允许成员使用无障碍服务操作手机；按任务独占。", draft.optBoolean("device"), value ->
              putQuiet(draft, "device", value));
      switchRow(activity, tools, "浏览器开发工具",
          "允许成员打开开发浏览器检查页面；按任务独占。", draft.optBoolean("browser"), value ->
              putQuiet(draft, "browser", value));

      sheet.scroll.post(() -> {
        if (!activity.isDestroyed()) {
          int max = Math.max(0, sheet.body.getHeight() - sheet.scroll.getHeight());
          sheet.scroll.scrollTo(0, Math.min(scrollY, max));
        }
      });
    };
    render[0].run();

    Button save = NativeUi.primaryButton(activity, "保存成员", () -> {
      String model = draft.optString("model").trim();
      String role = draft.optString("role").trim();
      if (model.isEmpty()) {
        error.setText("请选择模型，或在模型页手动输入模型 ID");
        error.setVisibility(View.VISIBLE);
        sheet.scroll.smoothScrollTo(0, 0);
        return;
      }
      if (role.isEmpty()) {
        error.setText("请选择或填写职责 / 分工，避免成员重复做相同任务");
        error.setVisibility(View.VISIBLE);
        sheet.scroll.smoothScrollTo(0, 0);
        return;
      }
      error.setVisibility(View.GONE);
      String engine = engineId(draft.optString("engine"));
      String name = draft.optString("name").trim();
      if (name.isEmpty()) name = NativeUi.engineName(engine);
      saved[0] = true;
      sheet.dialog.dismiss();
      JSONObject result = NativeUi.json(
          "id", draft.optString("id", UUID.randomUUID().toString()),
          "name", name,
          "role", role,
          "engine", engine,
          "model", model,
          "effort", draft.optString("effort", ""),
          "permission", draft.optString("permission", "ask"),
          "apiMode", draft.optString("apiMode", "current"),
          "device", draft.optBoolean("device"),
          "browser", draft.optBoolean("browser"));
      handler.postDelayed(() -> {
        if (!activity.isDestroyed()) done.accept(result);
      }, 120);
    });
    sheet.footer.addView(save);
    final TextView hint = footerHint(activity, sheet.footer,
        "下滑可设置手机 / 浏览器工具；保存只影响下一轮。");
    sheet.scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> hint.setText(y > 0
        ? "保存只影响下一轮；正在运行的任务保持原快照。"
        : "下滑可设置手机 / 浏览器工具；保存只影响下一轮。"));
    sheet.dialog.setOnDismissListener(d -> {
      if (saved[0] || cancelled == null) return;
      handler.postDelayed(() -> {
        if (!activity.isDestroyed()) cancelled.run();
      }, 120);
    });
  }

  private static void putQuiet(JSONObject value, String key, Object item) {
    try {
      value.put(key, item);
    } catch (Exception ignored) {}
  }
  private static void settingRow(Activity a, LinearLayout group, String label, String value,
      Runnable click) {
    if (group.getChildCount() > 0) group.addView(Ui.divider(a, 16));
    LinearLayout row = Ui.row(a);
    Ui.pad(row, 14, 13, 12, 13);
    row.setMinimumHeight(dp(a, 56));
    row.addView(Ui.text(a, label, 13, Ui.muted, null));
    boolean empty = value == null || value.trim().isEmpty();
    TextView current = Ui.text(a, empty ? "未设置" : value, 14.5f, empty ? Ui.faint : Ui.text,
        Ui.medium(a));
    current.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    current.setSingleLine(true);
    current.setEllipsize(TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams currentSpace = new LinearLayout.LayoutParams(0, -2, 1f);
    currentSpace.leftMargin = dp(a, 12);
    row.addView(current, currentSpace);
    ImageView arrow = new ImageView(a);
    arrow.setImageResource(R.drawable.ic_chevron);
    arrow.setColorFilter(Ui.faint);
    LinearLayout.LayoutParams arrowSpace = new LinearLayout.LayoutParams(dp(a, 18), dp(a, 18));
    arrowSpace.leftMargin = dp(a, 6);
    row.addView(arrow, arrowSpace);
    Ui.touchable(row);
    Ui.rippleRounded(row, 14, Ui.text);
    row.setOnClickListener(v -> click.run());
    group.addView(row);
  }
  private void rolePage(JSONObject draft, Runnable after) {
    final Sheet sheet = page(activity, "职责 / 分工",
        "选择内置职责，或自定义并保存为常用项。", 0.72);
    if (sheet == null) return;
    java.util.List<String> roles = new ArrayList<>(ROLE_PRESETS);
    for (String custom : customRoles()) if (!roles.contains(custom)) roles.add(custom);
    String current = draft.optString("role");
    if (!current.isEmpty() && !roles.contains(current)) roles.add(0, current);
    LinearLayout list = Ui.group(sheet.body);
    for (String role : roles) {
      final String value = role;
      choiceRow(activity, list, role, null, role.equals(current), () -> {
        putQuiet(draft, "role", value);
        sheet.dialog.dismiss();
        after.run();
      });
    }
    sheet.footer.addView(NativeUi.primaryButton(activity, "+ 自定义职责", () ->
        NativeUi.prompt(activity, "自定义职责", "例如：负责数据迁移与回滚验证", current, false, value -> {
          String role = value.trim();
          putQuiet(draft, "role", role);
          rememberRole(role);
          sheet.dialog.dismiss();
          after.run();
        })));
    footerHint(activity, sheet.footer, "自定义内容会保存到本机常用职责，下次可直接选择。");
  }
  private java.util.List<String> customRoles() {
    String raw = activity.getSharedPreferences("collaboration-config", 0).getString("roles", "");
    java.util.List<String> roles = new ArrayList<>();
    if (raw == null || raw.isEmpty()) return roles;
    for (String role : raw.split("\n")) {
      role = role.trim();
      if (!role.isEmpty() && !roles.contains(role)) roles.add(role);
    }
    return roles;
  }
  private void rememberRole(String role) {
    if (role.isEmpty() || ROLE_PRESETS.contains(role)) return;
    java.util.List<String> roles = customRoles();
    if (roles.contains(role)) return;
    roles.add(role);
    while (roles.size() > 12) roles.remove(0);
    activity.getSharedPreferences("collaboration-config", 0).edit()
        .putString("roles", TextUtils.join("\n", roles)).apply();
  }
  private void chooseAgent(JSONObject draft, Runnable after) {
    choicePage("选择 Agent", "不同 Agent 共用同一套环境与工作区。",
        Arrays.asList(AGENT_LABELS), null, indexOf(AGENTS, draft.optString("engine")),
        chosen -> {
          if (chosen < 0) return;
          putQuiet(draft, "engine", AGENTS[chosen]);
          alignModel(draft, after);
        });
  }
  private void chooseApi(JSONObject draft, Runnable after) {
    choicePage("API 来源", "当前 API 配置会跟随该 Agent 在设置中的选择。",
        Arrays.asList(API_LABELS), null, indexOf(API_IDS, draft.optString("apiMode", "current")),
        chosen -> {
          if (chosen < 0) return;
          putQuiet(draft, "apiMode", API_IDS[chosen]);
          alignModel(draft, after);
        });
  }
  private void alignModel(JSONObject draft, Runnable after) {
    modelsFor(draft.optString("engine"), draft.optString("apiMode", "current"), models -> {
      String current = draft.optString("model");
      if (models.isEmpty()) putQuiet(draft, "model", "");
      else if (!models.contains(current)) putQuiet(draft, "model", models.get(0));
      after.run();
    }, e -> after.run());
  }
  private void chooseModel(JSONObject draft, Runnable after) {
    modelsFor(draft.optString("engine"), draft.optString("apiMode", "current"), models -> {
      if (models.isEmpty()) {
        manualModel(draft, after);
        return;
      }
      final Sheet sheet = page(activity, "选择模型",
          NativeUi.engineName(draft.optString("engine")) + " · " + apiLabel(draft.optString("apiMode")),
          0.7);
      if (sheet == null) return;
      String current = draft.optString("model");
      LinearLayout list = Ui.group(sheet.body);
      for (String model : models) {
        final String value = model;
        choiceRow(activity, list, model, model.equals(current) ? "当前使用" : null,
            model.equals(current), () -> {
              putQuiet(draft, "model", value);
              sheet.dialog.dismiss();
              after.run();
            });
      }
      sheet.footer.addView(NativeUi.outlinedButton(activity, "手动输入模型 ID", () -> {
        sheet.dialog.dismiss();
        manualModel(draft, after);
      }));
      footerHint(activity, sheet.footer, "列表来自该 Agent 当前 API 来源已配置的模型。");
    }, e -> NativeUi.error(activity, e));
  }
  private void manualModel(JSONObject draft, Runnable after) {
    NativeUi.prompt(activity, "模型 ID", "按服务商文档填写模型 ID", draft.optString("model"), false,
        value -> {
          putQuiet(draft, "model", value.trim());
          after.run();
        });
  }
  private void modelsFor(String engine, String apiMode, Consumer<java.util.List<String>> done,
      Consumer<String> failed) {
    NativeEngine.call("provider/read", NativeUi.json("engine", engine), r -> {
      JSONObject profiles = r.optJSONObject("profiles");
      String source = "current".equals(apiMode) ? r.optString("mode", "thirdparty") : apiMode;
      JSONObject profile = profiles == null ? null : profiles.optJSONObject(source);
      JSONArray models = profile == null ? null : profile.optJSONArray("models");
      ArrayList<String> ids = new ArrayList<>();
      for (int i = 0; models != null && i < models.length(); i++) {
        String id = models.optString(i).trim();
        if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
      }
      if (activity.isDestroyed()) return;
      done.accept(ids);
    }, e -> {
      if (!activity.isDestroyed()) failed.accept(e);
    });
  }

  // ---- quick per-member field editing ------------------------------------
  void memberField(String field) {
    if (activity.isFinishing()) return;
    if (host.thread() == null) {
      ensureThread(() -> memberField(field));
      return;
    }
    final JSONArray people = participants();
    if (people.length() == 0) {
      NativeUi.toast(activity, "还没有协作成员");
      return;
    }
    String title = field.equals("model") ? "成员模型" : field.equals("effort") ? "成员思考强度" : "成员权限";
    String subtitle = field.equals("model")
        ? "每位成员可以使用不同 Agent 的模型。"
        : "修改后会应用到下一轮，不改变正在运行的实例。";
    final Sheet sheet = page(activity, title, subtitle, 0.72);
    if (sheet == null) return;
    LinearLayout list = Ui.group(sheet.body);
    for (int i = 0; i < people.length(); i++) {
      final int index = i;
      final JSONObject person = people.optJSONObject(i);
      if (person == null) continue;
      String value = field.equals("model") ? person.optString("model", "未选择")
          : field.equals("effort") ? effortLabel(person.optString("effort"))
          : permissionLabel(person.optString("permission"));
      String role = person.optString("role");
      String detail = NativeUi.engineName(person.optString("engine")) + " · " + value
          + (role.isEmpty() ? "" : "\n" + role);
      Ui.rowItem(list, R.drawable.ic_spark, memberTitle(person), detail,
          () -> {
            sheet.dialog.dismiss();
            chooseField(people, index, field);
          });
    }
    sheet.footer.addView(NativeUi.primaryButton(activity, "完成", sheet.dialog::dismiss));
  }
  private void chooseField(JSONArray people, int index, String field) {
    JSONObject person = people.optJSONObject(index);
    if (person == null) return;
    if (field.equals("model")) {
      modelsFor(person.optString("engine"), person.optString("apiMode", "current"), ids -> {
        if (ids.isEmpty()) {
          NativeUi.toast(activity, "当前 API 来源没有可用模型，请在成员设置中配置");
          return;
        }
        choicePage("选择模型", memberTitle(person) + " · " + NativeUi.engineName(person.optString("engine")),
            ids, null, ids.indexOf(person.optString("model")), chosen -> {
              if (chosen < 0 || chosen >= ids.size()) return;
              applyField(people, index, "model", ids.get(chosen));
            });
      }, e -> NativeUi.error(activity, e));
      return;
    }
    if (field.equals("effort")) {
      choicePage("思考强度", memberTitle(person) + " · " + person.optString("model"),
          Arrays.asList(EFFORT_LABELS), null, indexOf(EFFORT_IDS, person.optString("effort")),
          chosen -> {
            if (chosen >= 0) applyField(people, index, "effort", EFFORT_IDS[chosen]);
          });
      return;
    }
    choicePage("权限模式", memberTitle(person) + " · " + person.optString("model"),
        Arrays.asList(PERMISSION_LABELS),
        Arrays.asList("执行命令或修改文件前请求批准", "直接执行，不再逐次询问"),
        indexOf(PERMISSION_IDS, person.optString("permission", "ask")),
        chosen -> {
          if (chosen >= 0) applyField(people, index, "permission", PERMISSION_IDS[chosen]);
        });
  }
  private void applyField(JSONArray people, int index, String field, String value) {
    try {
      people.getJSONObject(index).put(field, value);
    } catch (Exception ignored) {}
    String mode = enabled() ? "collaboration" : "independent";
    rpc("session/config", NativeUi.json("threadId", id(), "mode", mode, "participants", people), r -> {
      host.controls();
      NativeUi.toast(activity, "成员配置已更新");
    });
  }
  private void choicePage(String title, String subtitle, java.util.List<String> entries,
      java.util.List<String> notes, int selected, Consumer<Integer> done) {
    final Sheet sheet = page(activity, title, subtitle, 0.7);
    if (sheet == null) return;
    LinearLayout list = Ui.group(sheet.body);
    for (int i = 0; i < entries.size(); i++) {
      final int index = i;
      String note = notes != null && i < notes.size() ? notes.get(i) : null;
      choiceRow(activity, list, entries.get(i), note, i == selected, () -> {
        sheet.dialog.dismiss();
        handler.postDelayed(() -> {
          if (!activity.isDestroyed()) done.accept(index);
        }, 120);
      });
    }
    sheet.footer.addView(NativeUi.outlinedButton(activity, "取消", sheet.dialog::dismiss));
  }

  // ---- resource limits ----------------------------------------------------
  static void resources(Activity a) {
    if (a.isFinishing() || a.isDestroyed()) return;
    NativeEngine.call("native/collaboration/settings", new JSONObject(), r -> {
      if (a.isFinishing() || a.isDestroyed()) return;
      final Sheet sheet = page(a, "协作与资源管理",
          "所有普通会话和协作任务共同使用一份执行名额。", 0.86);
      if (sheet == null) return;
      final int[] parallel = {r.optInt("parallel", 2)};
      final int[] source = {r.optInt("sourceParallel", 2)};

      section(a, sheet, "并行");
      numberCard(a, sheet.body, "全局并行上限",
          "所有普通会话与协作任务合计最多同时执行几个。", parallel);
      numberCard(a, sheet.body, "同 API 网关并发",
          "同一个 API 来源同时发起几个请求，避免触发服务商限流。", source);
      note(a, sheet.body, "默认同时运行 2 个任务，重任务最多 1 个；并行 3 属于实验选项。");

      section(a, sheet, "保护");
      NativeUi.statusCard(a, sheet.body,
          "可用内存低于 1 GiB、系统报告低内存或严重发热时暂停新派发。已经运行的任务不会被假装冻结。手机与浏览器资源按任务独占。");

      section(a, sheet, "工作区");
      NativeUi.statusCard(a, sheet.body,
          "每次执行使用独立工作区。Git 项目须先提交现有改动；非 Git 使用上限 200 MiB 的独立快照，成果不会自动覆盖原项目。");

      sheet.footer.addView(NativeUi.primaryButton(a, "保存资源设置", () ->
          NativeEngine.call("native/collaboration/settings",
              NativeUi.json("parallel", parallel[0], "sourceParallel", source[0]), value -> {
                NativeUi.toast(a, "已保存，新任务按新上限排队");
                sheet.dialog.dismiss();
              }, e -> NativeUi.error(a, e))));
      footerHint(a, sheet.footer, "资源设置对所有会话生效；已在运行的任务保持原名额。");
    }, e -> NativeUi.error(a, e));
  }
  private static void numberCard(Activity a, LinearLayout parent, String title, String detail,
      int[] selected) {
    LinearLayout group = Ui.group(parent);
    LinearLayout box = Ui.col(a);
    Ui.pad(box, 14, 12, 14, 12);
    box.addView(Ui.text(a, title, 15, Ui.text, Ui.medium(a)));
    TextView caption = Ui.text(a, detail, 12.5f, Ui.muted, null);
    caption.setLineSpacing(dp(a, 3), 1f);
    LinearLayout.LayoutParams captionSpace = new LinearLayout.LayoutParams(-1, -2);
    captionSpace.topMargin = dp(a, 3);
    box.addView(caption, captionSpace);

    LinearLayout track = Ui.row(a);
    track.setBackground(Ui.round(a, Ui.cardHigh, 999, 0.8f, Ui.lineSoft));
    track.setPadding(dp(a, 3), dp(a, 3), dp(a, 3), dp(a, 3));
    final TextView[] tabs = new TextView[3];
    final Runnable[] paint = new Runnable[1];
    paint[0] = () -> {
      for (int i = 0; i < tabs.length; i++) {
        boolean active = selected[0] == i + 1;
        tabs[i].setTextColor(active ? Ui.onAccentSoft : Ui.muted);
        tabs[i].setTypeface(active ? Ui.medium(a) : null);
        tabs[i].setBackground(active ? Ui.round(a, Ui.accentSoft, 999, 0, 0) : null);
      }
    };
    for (int i = 0; i < 3; i++) {
      final int value = i + 1;
      TextView tab = Ui.text(a, String.valueOf(value), 13.5f, Ui.muted, null);
      tab.setGravity(Gravity.CENTER);
      tab.setOnClickListener(v -> {
        selected[0] = value;
        paint[0].run();
      });
      track.addView(tab, new LinearLayout.LayoutParams(0, dp(a, 38), 1f));
      tabs[i] = tab;
    }
    paint[0].run();
    LinearLayout.LayoutParams trackSpace = new LinearLayout.LayoutParams(-1, dp(a, 44));
    trackSpace.topMargin = dp(a, 10);
    box.addView(track, trackSpace);
    group.addView(box);
  }

  // ---- execution panel ----------------------------------------------------
  void submit(JSONArray input, Consumer<JSONObject> done, Consumer<String> failed) {
    send("native/chat/queue/submit", input, done, failed);
  }
  /** Idle collaboration runs immediately; the queue is only used while a task is active. */
  void submitNow(JSONArray input, Consumer<JSONObject> done, Consumer<String> failed) {
    send("native/collaboration/session/submit", input, done, failed);
  }
  private void send(String method, JSONArray input, Consumer<JSONObject> done, Consumer<String> failed) {
    String target = id(), fingerprint = target + input.toString();
    android.content.SharedPreferences prefs = activity.getSharedPreferences("conversation-collaboration", 0);
    String request = prefs.getString("pendingFingerprint", "").equals(fingerprint)
        ? prefs.getString("pendingRequest", UUID.randomUUID().toString())
        : UUID.randomUUID().toString();
    prefs.edit().putString("pendingFingerprint", fingerprint).putString("pendingRequest", request).apply();
    JSONObject args = NativeUi.json("threadId", target, "input", input, "clientRequestId", request);
    if (method.equals("native/chat/queue/submit")) {
      try { args.put("mode", "collaboration"); } catch (JSONException ignored) {}
    }
    NativeEngine.call(method, args, r -> {
      if (activity.isDestroyed()) return;
      prefs.edit().remove("pendingFingerprint").remove("pendingRequest").apply();
      if (target.equals(id()) && r.optBoolean("queued")) NativeUi.toast(activity, r.optString("message", "任务已排队"));
      done.accept(r);
      if (target.equals(id())) refresh();
    }, e -> {
      if (!activity.isDestroyed()) failed.accept(e);
    });
  }
  void stop() {
    JSONArray turns = host.thread() == null ? null : host.thread().optJSONArray("turns");
    for (int i = 0; turns != null && i < turns.length(); i++) {
      JSONObject turn = turns.optJSONObject(i);
      if (turn.has("workflowId") &&
          (Arrays.asList("running", "paused", "stopping").contains(turn.optString("collaborationStatus")) ||
           turn.optInt("collaborationActive") > 0))
        control("cancel", turn.optString("workflowId"), "", null);
    }
  }
  private void control(String action, String workflow, String task, JSONObject extra) {
    JSONObject args = extra == null ? new JSONObject() : extra;
    try {
      args.put("workflowId", workflow).put("taskId", task);
    } catch (Exception ignored) {}
    rpc(action, args, r -> host.controls());
  }
  private Boolean v2;
  void round(String workflow) {
    if (Boolean.TRUE.equals(v2)) { roundV2(workflow); return; }
    if (Boolean.FALSE.equals(v2)) { roundLegacy(workflow); return; }
    NativeEngine.call("native/collaboration/capabilities", new JSONObject(), r -> {
      v2 = r.optInt("version", 0) >= 2;
      if (v2) roundV2(workflow); else roundLegacy(workflow);
    }, e -> { v2 = false; roundLegacy(workflow); });
  }
  private void roundLegacy(String workflow) {
    NativeEngine.call("native/collaboration/read", NativeUi.json("workflowId", workflow), r -> {
      if (!owns(workflow)) return;
      JSONObject doc = r.optJSONObject("workflow");
      if (doc == null) return;
      if (!id().equals(doc.optString("conversationId"))) {
        NativeUi.toast(activity, "这是编辑前的历史协作记录，可在当前输入框提交新任务");
        return;
      }
      String status = doc.optString("status");
      JSONArray tasks = doc.optJSONArray("tasks");
      int total = tasks == null ? 0 : tasks.length();
      int completed = doc.optInt("completed");
      final Sheet sheet = page(activity, "本轮协作",
          state(status) + " · " + completed + " / " + total + " 个任务完成", 0.9);
      if (sheet == null) return;

      section(activity, sheet, "本轮操作");
      LinearLayout actions = Ui.group(sheet.body);
      if (status.equals("running"))
        Ui.rowItem(actions, R.drawable.ic_stop, "暂停新派发", "已运行的任务继续完成",
            () -> { sheet.dialog.dismiss(); control("pause", workflow, "", null); });
      if (status.equals("paused") || status.equals("partial_failure"))
        Ui.rowItem(actions, R.drawable.ic_spark, "继续派发", "按原计划调度下一个任务",
            () -> { sheet.dialog.dismiss(); control("resume", workflow, "", null); });
      if (status.equals("awaiting_acceptance"))
        Ui.rowItem(actions, R.drawable.ic_check, "确认本轮成果", "确认后本轮标记为完成",
            () -> { sheet.dialog.dismiss(); control("accept", workflow, "", null); });
      if (Arrays.asList("running", "paused").contains(status))
        Ui.rowItem(actions, R.drawable.ic_close, "停止本轮协作", "中断正在运行的任务并保留记录",
            () -> { sheet.dialog.dismiss(); control("cancel", workflow, "", null); });
      Ui.rowItem(actions, R.drawable.ic_spark, "刷新进度", "重新读取任务状态与用量",
          () -> {
            sheet.dialog.dismiss();
            handler.postDelayed(() -> round(workflow), 120);
          });

      section(activity, sheet, "任务");
      for (int i = 0; tasks != null && i < tasks.length(); i++) {
        JSONObject task = tasks.optJSONObject(i);
        if (task == null) continue;
        boolean active = Arrays.asList("starting", "running", "waiting_input", "stopping")
            .contains(task.optString("status"));
        LinearLayout card = Ui.group(sheet.body);
        LinearLayout box = Ui.col(activity);
        Ui.pad(box, 14, 12, 14, 8);
        TextView title = Ui.text(activity, (i + 1) + " · " + task.optString("title"), 15, Ui.text,
            Ui.medium(activity));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(title);
        TextView meta = Ui.text(activity, state(task.optString("status")) + " · "
            + participantMeta(doc, task.optString("participantId")), 12.5f, Ui.muted, null);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaSpace = new LinearLayout.LayoutParams(-1, -2);
        metaSpace.topMargin = dp(activity, 3);
        box.addView(meta, metaSpace);
        String error = task.optString("error");
        if (!error.isEmpty()) {
          TextView failure = Ui.text(activity, error, 12, Ui.danger, null);
          failure.setMaxLines(3);
          failure.setEllipsize(TextUtils.TruncateAt.END);
          failure.setLineSpacing(dp(activity, 2), 1f);
          LinearLayout.LayoutParams failSpace = new LinearLayout.LayoutParams(-1, -2);
          failSpace.topMargin = dp(activity, 5);
          box.addView(failure, failSpace);
        } else {
          String wait = task.optString("waitReason");
          if (!wait.isEmpty()) {
            TextView waiting = Ui.text(activity, wait, 12, Ui.faint, null);
            waiting.setMaxLines(2);
            waiting.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams waitSpace = new LinearLayout.LayoutParams(-1, -2);
            waitSpace.topMargin = dp(activity, 5);
            box.addView(waiting, waitSpace);
          }
        }
        if (active) {
          Button stop = NativeUi.textButton(activity, "停止此成员任务",
              () -> { sheet.dialog.dismiss(); control("stop", workflow, task.optString("id"), null); });
          stop.setTextColor(Ui.danger);
          box.addView(stop);
        } else {
          box.addView(NativeUi.textButton(activity, "继续 / 重试此任务", () ->
              new MaterialAlertDialogBuilder(activity).setTitle("新建一次执行尝试？")
                  .setMessage("保留已有记录和成果；模型请求会产生新的用量。")
                  .setNegativeButton("取消", null)
                  .setPositiveButton("继续", (d, w) -> {
                    sheet.dialog.dismiss();
                    control("retry", workflow, task.optString("id"), null);
                  }).show()));
        }
        box.addView(NativeUi.textButton(activity, "更换执行者 / 模型", () -> {
          sheet.dialog.dismiss();
          participant(null, p -> {
            if (active)
              new MaterialAlertDialogBuilder(activity).setTitle("新配置生效时间")
                  .setItems(new String[] {"当前执行完成后生效", "停止当前执行并交接"}, (d, which) ->
                      control("reassign", workflow, task.optString("id"),
                          NativeUi.json("participant", p, "when", which == 0 ? "after" : "interrupt")))
                  .show();
            else
              control("reassign", workflow, task.optString("id"), NativeUi.json("participant", p));
          }, null);
        }));
        JSONObject artifact = task.optJSONObject("artifact");
        if (artifact != null && !artifact.optString("patch").isEmpty()
            && task.optString("status").equals("succeeded"))
          box.addView(NativeUi.textButton(activity, "检查并合入项目", () ->
              new MaterialAlertDialogBuilder(activity).setTitle("合入此成员的补丁？")
                  .setMessage("仅在原项目无改动且基线一致时应用，冲突会停止，不自动提交。")
                  .setNegativeButton("取消", null)
                  .setPositiveButton("合入", (d, w) -> {
                    sheet.dialog.dismiss();
                    control("integrate", workflow, task.optString("id"), null);
                  }).show()));
        card.addView(box);
      }
      if (total == 0) note(activity, sheet.body, "本轮还没有派发任务。");
      sheet.footer.addView(NativeUi.primaryButton(activity, "完成", sheet.dialog::dismiss));
      boolean live = Arrays.asList("running", "paused", "partial_failure", "awaiting_acceptance")
          .contains(status);
      footerHint(activity, sheet.footer, live
          ? "协作在后台继续；关闭此页不会停止任务。"
          : "本轮已结束；重试或改派只影响该任务。");
    }, e -> NativeUi.error(activity, e));
  }
  // ---- v2 delivery console ------------------------------------------------
  private void roundV2(String workflow) {
    NativeEngine.call("native/collaboration/read", NativeUi.json("workflowId", workflow), r -> {
      if (!owns(workflow) || activity.isDestroyed()) return;
      JSONObject doc = r.optJSONObject("workflow");
      if (doc == null) {
        NativeUi.toast(activity, "本轮协作已不存在");
        return;
      }
      JSONArray tasks = doc.optJSONArray("tasks");
      JSONObject delivery = doc.optJSONObject("delivery");
      JSONObject usage = doc.optJSONObject("budgetUsage");
      int total = tasks == null ? 0 : tasks.length();
      int verified = delivery == null ? 0 : delivery.optInt("verifiedTasks");
      String deliveryStatus = delivery == null ? "executing" : delivery.optString("status");
      String headline = CollaborationStatus.execution(doc.optString("status"));
      if (delivery != null) headline = CollaborationStatus.delivery(deliveryStatus);
      String subtitle = "执行 " + headline
          + " · 验证 " + verified + " / " + total
          + " · 验收 " + CollaborationStatus.delivery(deliveryStatus);
      final Sheet sheet = page(activity, "本轮协作", subtitle, 0.94);
      if (sheet == null) return;

      section(activity, sheet, "整轮状态");
      LinearLayout overview = Ui.group(sheet.body);
      LinearLayout pills = Ui.row(activity);
      Ui.pad(pills, 14, 12, 14, 8);
      addPill(pills, Ui.pill(activity, "执行 · " + headline,
          deliveryColor(deliveryStatus), Ui.alpha(deliveryColor(deliveryStatus), 0.12f)));
      addPill(pills, Ui.pill(activity, "验证 · " + verified + " / " + total,
          verified == total && total > 0 ? Ui.mint : Ui.warn,
          Ui.alpha(verified == total && total > 0 ? Ui.mint : Ui.warn, 0.12f)));
      addPill(pills, Ui.pill(activity, "验收 · " + CollaborationStatus.delivery(deliveryStatus),
          deliveryColor(deliveryStatus), Ui.alpha(deliveryColor(deliveryStatus), 0.12f)));
      overview.addView(pills);

      String budget = CollaborationStatus.budgetLine(usage);
      if (!budget.isEmpty()) cardNote(activity, overview, budget);
      JSONArray pauses = doc.optJSONArray("pauseReasons");
      if (pauses != null && pauses.length() > 0) {
        StringBuilder why = new StringBuilder();
        for (int i = 0; i < pauses.length(); i++) {
          if (why.length() > 0) why.append(" · ");
          why.append(CollaborationStatus.pauseReason(pauses.optString(i)));
        }
        cardNote(activity, overview, "暂停原因：" + why);
      }
      if (delivery != null && delivery.optJSONArray("manualRequiredTaskIds") != null
          && delivery.optJSONArray("manualRequiredTaskIds").length() > 0) {
        cardNote(activity, overview, "有任务需要人工核验；这不等于自动测试通过。");
      }
      if (delivery != null && delivery.optJSONObject("acceptance") != null) {
        cardNote(activity, overview, "已生成验收回执；回执记录各任务的尝试、验证状态和证据哈希。");
      }
      int reworkRounds = doc.optInt("reworkRounds");
      JSONObject rework = doc.optJSONObject("rework");
      if (rework != null && rework.optBoolean("auto")) {
        cardNote(activity, overview, "自动返工：开启 · 已返工 " + reworkRounds + " / " + rework.optInt("maxRounds", 2));
      }

      section(activity, sheet, "本轮操作");
      LinearLayout actions = Ui.group(sheet.body);
      String workflowStatus = doc.optString("status");
      if (workflowStatus.equals("running"))
        Ui.rowItem(actions, R.drawable.ic_stop, "暂停新派发", "已运行的任务继续完成",
            () -> { sheet.dialog.dismiss(); control("pause", workflow, "", null); });
      if (workflowStatus.equals("paused") || workflowStatus.equals("partial_failure"))
        Ui.rowItem(actions, R.drawable.ic_spark, "继续派发", "按原计划调度下一个任务",
            () -> { sheet.dialog.dismiss(); control("resume", workflow, "", null); });
      if (workflowStatus.equals("awaiting_acceptance"))
        Ui.rowItem(actions, R.drawable.ic_check_circle, "确认本轮成果", "人工验收；不会自动提交 Git",
            () -> confirm("确认本轮成果？", "人工验收只表示你检查过，不等于自动测试通过，也不会把补丁提交到 Git。", () -> {
              sheet.dialog.dismiss();
              control("accept", workflow, "", null);
            }));
      if (Arrays.asList("running", "paused", "stopping").contains(workflowStatus))
        Ui.rowItem(actions, R.drawable.ic_close, "停止本轮协作", "中断正在运行的任务并保留记录",
            () -> confirm("停止本轮协作？", "正在执行的任务会被中断，成果和过程会保留。", () -> {
              sheet.dialog.dismiss();
              control("cancel", workflow, "", null);
            }));
      Ui.rowItem(actions, R.drawable.ic_send, "补充要求", "保存为下次尝试生效，或中断后交接",
          () -> message(workflow, ""));
      Ui.rowItem(actions, R.drawable.ic_settings, "策略与预算", "次数、时间、已上报词元、返工",
          () -> policy(workflow, doc));
      Ui.rowItem(actions, R.drawable.ic_history, "协作模板", "选择下一轮的默认分工方式",
          () -> template(workflow, doc));
      if (hasRecovery(doc))
        Ui.rowItem(actions, R.drawable.ic_warning, "中断恢复检查", "先检查旧进程和文件，再决定是否继续",
            () -> {
              sheet.dialog.dismiss();
              recovery(workflow, "");
            });
      if (workflowStatus.equals("completed") || doc.optBoolean("accepted"))
        Ui.rowItem(actions, R.drawable.ic_folder, "清理隔离工作区", "预览待清理目录；不会删除原项目",
            () -> cleanup(workflow));
      Ui.rowItem(actions, R.drawable.ic_history, "刷新进度", "重新读取任务状态与用量", () -> {
        sheet.dialog.dismiss();
        handler.postDelayed(() -> roundV2(workflow), 120);
      });

      section(activity, sheet, "任务");
      Set<String> selected = new HashSet<>();
      for (int i = 0; tasks != null && i < tasks.length(); i++) {
        JSONObject task = tasks.optJSONObject(i);
        if (task != null) taskCard(sheet, workflow, doc, task, i, selected);
      }
      if (total == 0) note(activity, sheet.body, "本轮还没有派发任务。");
      if (total > 0) {
        Button integrate = NativeUi.outlinedButton(activity, "预览并合入已勾选的成果", () -> {
          if (selected.isEmpty()) {
            NativeUi.toast(activity, "请先在任务卡勾选要合入的成果");
            return;
          }
          integration(workflow, doc, selected);
        });
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
        space.topMargin = dp(activity, 12);
        sheet.body.addView(integrate, space);
      }
      sheet.footer.addView(NativeUi.primaryButton(activity, "完成", sheet.dialog::dismiss));
      footerHint(activity, sheet.footer, "验收看后端状态和证据；自然语言里的“已完成”不作为成功依据。");
    }, e -> NativeUi.error(activity, e));
  }

  private static void addPill(LinearLayout row, TextView pill) {
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-2, -2);
    space.rightMargin = dp((Activity) row.getContext(), 6);
    row.addView(pill, space);
  }

  private static int statusColor(String status) {
    if (status == null) return Ui.muted;
    switch (status) {
      case "succeeded": case "completed": case "accepted": return Ui.mint;
      case "failed": case "verification_failed": case "needs_reconciliation": return Ui.danger;
      case "paused": case "partial_failure": case "cancelled": case "interrupted": return Ui.warn;
      default: return Ui.accent;
    }
  }

  private static int deliveryColor(String status) {
    if (status == null) return Ui.warn;
    if (status.equals("accepted")) return Ui.mint;
    if (status.equals("verification_failed")) return Ui.danger;
    if (status.equals("awaiting_acceptance")) return Ui.accent;
    return Ui.warn;
  }

  private static boolean hasRecovery(JSONObject doc) {
    JSONArray tasks = doc.optJSONArray("tasks");
    for (int i = 0; tasks != null && i < tasks.length(); i++) {
      JSONObject task = tasks.optJSONObject(i);
      if (task != null && task.optString("status").equals("needs_reconciliation")) return true;
    }
    return false;
  }

  private void taskCard(Sheet sheet, String workflow, JSONObject doc, JSONObject task, int index, Set<String> selected) {
    String id = task.optString("id");
    String exec = task.optString("executionStatus", task.optString("status"));
    String verify = task.optString("verificationStatus", "not_run");
    boolean active = Arrays.asList("starting", "running", "waiting_input", "stopping").contains(exec);
    boolean needsCheck = exec.equals("needs_reconciliation");
    LinearLayout group = Ui.group(sheet.body);
    LinearLayout box = Ui.col(activity);
    Ui.pad(box, 14, 12, 14, 10);

    LinearLayout titleRow = Ui.row(activity);
    titleRow.setGravity(16);
    TextView title = Ui.text(activity, (index + 1) + " · " + task.optString("title"), 15, Ui.text, Ui.medium(activity));
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
    TextView kind = Ui.pill(activity, CollaborationStatus.kind(task.optString("kind")), Ui.faint, Ui.cardHigh);
    titleRow.addView(kind);
    if (task.optBoolean("stale")) {
      TextView stale = Ui.pill(activity, "已过期", Ui.danger, Ui.alpha(Ui.danger, 0.12f));
      LinearLayout.LayoutParams staleSpace = new LinearLayout.LayoutParams(-2, -2);
      staleSpace.leftMargin = dp(activity, 5);
      titleRow.addView(stale, staleSpace);
    }
    box.addView(titleRow);
    LinearLayout.LayoutParams personSpace = new LinearLayout.LayoutParams(-1, -2);
    personSpace.topMargin = dp(activity, 4);
    box.addView(Ui.text(activity, participantMeta(doc, task.optString("participantId")), 12.0f, Ui.muted, null), personSpace);

    LinearLayout tags = Ui.row(activity);
    Ui.pad(tags, 0, 8, 0, 6);
    addPill(tags, Ui.pill(activity, "执行 · " + CollaborationStatus.execution(exec),
        statusColor(exec), Ui.alpha(statusColor(exec), 0.12f)));
    addPill(tags, Ui.pill(activity, "验证 · " + CollaborationStatus.verification(verify),
        verify.equals("passed") ? Ui.mint : verify.equals("failed") ? Ui.danger : Ui.warn,
        Ui.alpha(verify.equals("passed") ? Ui.mint : verify.equals("failed") ? Ui.danger : Ui.warn, 0.12f)));
    addPill(tags, Ui.pill(activity, "尝试 " + task.optInt("attemptsCount", task.optJSONArray("attempts") == null ? 0 : task.optJSONArray("attempts").length())
        + " / " + task.optInt("maxAttempts", 3), Ui.faint, Ui.cardHigh));
    box.addView(tags);

    String error = task.optString("error");
    if (!error.isEmpty()) {
      TextView failure = Ui.text(activity, error, 12, Ui.danger, null);
      failure.setMaxLines(4);
      failure.setEllipsize(TextUtils.TruncateAt.END);
      failure.setLineSpacing(dp(activity, 2), 1f);
      LinearLayout.LayoutParams failureSpace = new LinearLayout.LayoutParams(-1, -2);
      failureSpace.topMargin = dp(activity, 4);
      box.addView(failure, failureSpace);
    } else if (!task.optString("waitReason").isEmpty()) {
      TextView waiting = Ui.text(activity, task.optString("waitReason"), 12, Ui.faint, null);
      waiting.setMaxLines(2);
      waiting.setLineSpacing(dp(activity, 2), 1f);
      LinearLayout.LayoutParams waitingSpace = new LinearLayout.LayoutParams(-1, -2);
      waitingSpace.topMargin = dp(activity, 4);
      box.addView(waiting, waitingSpace);
    }
    verificationDetails(box, task);

    JSONObject artifact = task.optJSONObject("artifact");
    if (artifact != null) {
      StringBuilder evidence = new StringBuilder();
      if (!artifact.optString("patch").isEmpty()) evidencePart(evidence, "补丁");
      if (!artifact.optString("archive").isEmpty()) evidencePart(evidence, "归档");
      if (!artifact.optString("patchSha256").isEmpty()) evidencePart(evidence, shortHash(artifact.optString("patchSha256")));
      if (!artifact.optString("archiveSha256").isEmpty()) evidencePart(evidence, shortHash(artifact.optString("archiveSha256")));
      JSONArray artifactFiles = artifact.optJSONArray("files");
      if (artifactFiles != null && artifactFiles.length() > 0) evidencePart(evidence, artifactFiles.length() + " 个文件");
      if (!artifact.optString("integration").isEmpty()) evidencePart(evidence, artifact.optString("integration"));
      if (evidence.length() > 0) {
        TextView line = Ui.text(activity, "成果 · " + evidence, 11, Ui.faint, null);
        line.setMaxLines(2);
        line.setEllipsize(TextUtils.TruncateAt.END);
        line.setLineSpacing(dp(activity, 2), 1f);
        Ui.pad(line, 0, 6, 0, 0);
        box.addView(line);
        String patchPath = artifact.optString("patch");
        String archivePath = artifact.optString("archive");
        if (!patchPath.isEmpty() || !archivePath.isEmpty()) {
          box.addView(commandButton(!patchPath.isEmpty() ? "打开补丁文件" : "打开成果归档", () -> {
            if (activity instanceof NativeHomeActivity)
              ((NativeHomeActivity) activity).openEditor(!patchPath.isEmpty() ? patchPath : archivePath);
          }, false));
        }
      }
    }

    if (needsCheck) {
      box.addView(commandButton("检查并恢复", () -> {
        sheet.dialog.dismiss();
        recovery(workflow, id);
      }, false));
    } else {
      if (task.optBoolean("canRetry") && !active)
        box.addView(commandButton("重试 / 新尝试", () -> confirm("新建一次执行尝试？",
            "保留已有记录和成果；模型请求会产生新的用量。", () -> {
              sheet.dialog.dismiss();
              control("retry", workflow, id, null);
            }), false));
      box.addView(commandButton(active ? "停止此成员任务" : "更换执行者 / 模型", () -> {
        if (active) {
          confirm("停止此成员任务？", "已运行的内容会保留；之后可以检查成果并重试。", () -> {
            sheet.dialog.dismiss();
            control("stop", workflow, id, null);
          });
        } else {
          sheet.dialog.dismiss();
          participant(null, p -> control("reassign", workflow, id, NativeUi.json("participant", p)), null);
        }
      }, active));
      if (editable(doc) && !task.optString("status").equals("succeeded"))
        box.addView(commandButton("编辑任务配置", () -> {
          sheet.dialog.dismiss();
          taskConfig(workflow, doc, task);
        }, false));
    }
    box.addView(commandButton("交接摘要", () -> {
      sheet.dialog.dismiss();
      handoff(workflow, id);
    }, false));
    box.addView(commandButton("仅对此任务补充要求", () -> message(workflow, id), false));

    boolean hasPatch = artifact != null && !artifact.optString("patch").isEmpty();
    if (!active && !needsCheck && task.optString("status").equals("succeeded")
        && hasPatch && !task.optBoolean("stale")) {
      CheckBox pick = new CheckBox(activity);
      pick.setText("选择此成果合入");
      pick.setTextSize(12.0f);
      pick.setTextColor(Ui.muted);
      pick.setOnCheckedChangeListener((button, checked) -> {
        if (checked) selected.add(id); else selected.remove(id);
      });
      Ui.pad(pick, dp(activity, 2), dp(activity, 6), dp(activity, 2), dp(activity, 2));
      LinearLayout.LayoutParams pickSpace = new LinearLayout.LayoutParams(-1, -2);
      pickSpace.topMargin = dp(activity, 4);
      box.addView(pick, pickSpace);
    }
    group.addView(box);
  }

  private Button commandButton(String label, Runnable action, boolean danger) {
    Button button = NativeUi.textButton(activity, label, action);
    button.setAllCaps(false);
    button.setTextSize(12.5f);
    button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    button.setPadding(dp(activity, 3), dp(activity, 4), dp(activity, 3), dp(activity, 4));
    button.setMinHeight(dp(activity, 40));
    LinearLayout.LayoutParams buttonSpace = new LinearLayout.LayoutParams(-1, -2);
    buttonSpace.topMargin = dp(activity, 3);
    button.setLayoutParams(buttonSpace);
    if (danger) button.setTextColor(Ui.danger);
    return button;
  }

  private static void evidencePart(StringBuilder text, String value) {
    if (value == null || value.isEmpty()) return;
    if (text.length() > 0) text.append(" · ");
    text.append(value);
  }

  private static String shortHash(String value) {
    return value == null || value.length() < 12 ? value : value.substring(0, 12) + "…";
  }

  private void verificationDetails(LinearLayout box, JSONObject task) {
    JSONObject verification = task.optJSONObject("verification");
    if (verification == null) return;
    JSONArray checks = verification.optJSONArray("checks");
    int shown = 0;
    for (int i = 0; checks != null && i < checks.length() && shown < 4; i++, shown++) {
      JSONObject check = checks.optJSONObject(i);
      if (check == null) continue;
      String label;
      if (check.has("argv")) {
        JSONArray argv = check.optJSONArray("argv");
        StringBuilder command = new StringBuilder();
        for (int j = 0; argv != null && j < argv.length(); j++) {
          if (command.length() > 0) command.append(' ');
          command.append(argv.optString(j));
        }
        label = command.toString();
      } else if (!check.optString("path").isEmpty()) {
        label = check.optString("path");
      } else {
        label = "审核";
      }
      String detail = check.optString("error");
      if (detail.isEmpty() && !check.optString("output").isEmpty()) {
        detail = check.optString("output");
        if (detail.length() > 160) detail = detail.substring(detail.length() - 160);
      }
      if (check.has("exitCode")) detail = "退出码 " + check.optInt("exitCode") + (detail.isEmpty() ? "" : " · " + detail);
      TextView line = Ui.text(activity, (check.optBoolean("passed") ? "✓ " : "✗ ") + label
          + (detail.isEmpty() ? "" : "\n" + detail), 10.5f, check.optBoolean("passed") ? Ui.mint : Ui.danger, null);
      line.setMaxLines(3);
      line.setEllipsize(TextUtils.TruncateAt.END);
      line.setLineSpacing(dp(activity, 1), 1f);
      Ui.pad(line, 0, 3, 0, 0);
      box.addView(line);
      if (check.has("findings")) {
        JSONArray findings = check.optJSONArray("findings");
        for (int j = 0; findings != null && j < findings.length() && j < 3; j++) {
          TextView finding = Ui.text(activity, "· " + findings.optString(j), 10.5f, Ui.warn, null);
          finding.setMaxLines(2);
          finding.setEllipsize(TextUtils.TruncateAt.END);
          box.addView(finding);
        }
      }
    }
    JSONObject review = verification.optJSONObject("review");
    if (review != null) {
      JSONArray findings = review.optJSONArray("findings");
      for (int i = 0; findings != null && i < findings.length() && i < 3; i++) {
        TextView finding = Ui.text(activity, "· " + findings.optString(i), 10.5f, Ui.warn, null);
        finding.setMaxLines(3);
        finding.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(finding);
      }
    }
    if (verification.optString("status").equals("manual_required"))
      note(activity, box, "未配置自动验证，需人工核验；这不等于自动测试通过。");
    if (checks != null && checks.length() > shown) note(activity, box, "另有 " + (checks.length() - shown) + " 项验证未展开");
  }

  private static boolean editable(JSONObject doc) {
    String status = doc.optString("status");
    if (!Arrays.asList("draft", "paused", "partial_failure").contains(status)) return false;
    JSONArray tasks = doc.optJSONArray("tasks");
    for (int i = 0; tasks != null && i < tasks.length(); i++) {
      JSONObject task = tasks.optJSONObject(i);
      String taskStatus = task == null ? "" : task.optString("status");
      if (Arrays.asList("starting", "running", "waiting_input", "stopping", "needs_reconciliation").contains(taskStatus)) return false;
    }
    return true;
  }

  private void integration(String workflow, JSONObject doc, Set<String> selected) {
    if (selected.isEmpty()) {
      NativeUi.toast(activity, "请先勾选要合入的成果");
      return;
    }
    JSONArray ids = new JSONArray();
    for (String id : selected) ids.put(id);
    rpc("integration/preview", NativeUi.json("workflowId", workflow, "taskIds", ids), r -> {
      showIntegrationPreview(workflow, selected, r);
    });
  }

  private void showIntegrationPreview(String workflow, Set<String> selected, JSONObject result) {
    String status = result.optString("status");
    boolean canApply = result.optBoolean("canApply");
    LinearLayout box = Ui.col(activity);
    Ui.pad(box, 20, 8, 20, 8);
    String statusLabel = status.equals("unchanged") ? "没有净变化" : status.equals("blocked") ? "存在冲突"
        : status.equals("ready") ? "可以合入" : status;
    TextView state = Ui.text(activity, "预览状态：" + statusLabel,
        13, canApply ? Ui.mint : Ui.warn, Ui.medium(activity));
    box.addView(state);
    if (!result.optString("message").isEmpty()) {
      TextView reason = Ui.text(activity, result.optString("message"), 12, Ui.danger, null);
      reason.setLineSpacing(dp(activity, 2), 1f);
      LinearLayout.LayoutParams reasonSpace = new LinearLayout.LayoutParams(-1, -2);
      reasonSpace.topMargin = dp(activity, 4);
      box.addView(reason, reasonSpace);
    }
    JSONArray files = result.optJSONArray("changedFiles");
    StringBuilder list = new StringBuilder();
    for (int i = 0; files != null && i < files.length() && i < 30; i++) {
      if (i > 0) list.append('\n');
      list.append("· ").append(files.optString(i));
    }
    if (files != null && files.length() > 30) list.append("\n… 另有 ").append(files.length() - 30).append(" 个文件");
    if (list.length() > 0) {
      TextView changed = Ui.text(activity, "改动文件\n" + list, 11.5f, Ui.muted, null);
      changed.setTextIsSelectable(true);
      changed.setLineSpacing(dp(activity, 2), 1f);
      LinearLayout.LayoutParams changedSpace = new LinearLayout.LayoutParams(-1, -2);
      changedSpace.topMargin = dp(activity, 8);
      box.addView(changed, changedSpace);
    }
    String patch = result.optString("patch");
    if (!patch.isEmpty()) {
      if (patch.length() > 12000) patch = patch.substring(0, 12000) + "\n… 预览已截断";
      TextView diff = Ui.text(activity, patch, 10.5f, Ui.faint, Ui.mono(activity));
      diff.setTextIsSelectable(true);
      diff.setLineSpacing(dp(activity, 1), 1f);
      ScrollView scroll = new ScrollView(activity);
      int height = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.36f);
      scroll.addView(diff, new ScrollView.LayoutParams(-1, -2));
      LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, height);
      space.topMargin = dp(activity, 8);
      box.addView(scroll, space);
      if (result.optBoolean("patchTruncated")) note(activity, box, "预览文本已截断，应用时由后端按完整补丁处理。");
    }
    ScrollView outer = new ScrollView(activity);
    outer.setClipToPadding(false);
    outer.addView(box, new ScrollView.LayoutParams(-1, -2));
    LinearLayout holder = Ui.col(activity);
    int previewHeight = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.7f);
    holder.addView(outer, new LinearLayout.LayoutParams(-1, previewHeight));
    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
        .setTitle("合入预览")
        .setView(holder)
        .setNegativeButton("取消", null);
    if (canApply) builder.setPositiveButton("确认合入", (d, w) -> rpc("integration/apply",
        NativeUi.json("workflowId", workflow, "taskIds", taskIds(selected),
            "previewToken", result.optString("previewToken")), r -> {
          NativeUi.toast(activity, "已合入原项目，未提交 Git");
          refresh();
        }));
    builder.show();
  }

  private static JSONArray taskIds(Set<String> selected) {
    JSONArray ids = new JSONArray();
    for (String id : selected) ids.put(id);
    return ids;
  }

  private void confirm(String title, String message, Runnable action) {
    new MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(message)
        .setNegativeButton("取消", null).setPositiveButton("继续", (d, w) -> action.run()).show();
  }

  private void recovery(String workflow, String taskId) {
    rpc("recovery/read", NativeUi.json("workflowId", workflow, "taskId", taskId), r -> {
      JSONArray tasks = r.optJSONArray("tasks");
      final Sheet sheet = page(activity, "中断恢复", "先检查旧进程和文件，再决定是否继续；不会自动重放", 0.86);
      if (sheet == null) return;
      if (tasks == null || tasks.length() == 0) note(activity, sheet.body, "没有需要核对的执行。");
      for (int i = 0; tasks != null && i < tasks.length(); i++) {
        JSONObject task = tasks.optJSONObject(i);
        if (task == null) continue;
        LinearLayout card = Ui.group(sheet.body);
        LinearLayout box = Ui.col(activity);
        Ui.pad(box, 14, 12, 14, 10);
        box.addView(Ui.text(activity, task.optString("taskId") + " · " + CollaborationStatus.execution(task.optString("status")),
            14, Ui.text, Ui.medium(activity)));
        if (!task.optString("error").isEmpty()) note(activity, box, task.optString("error"));
        JSONObject workspace = task.optJSONObject("workspace");
        if (workspace != null && !workspace.optString("path").isEmpty()) note(activity, box, "工作区：" + workspace.optString("path"));
        JSONArray processes = task.optJSONArray("processes");
        boolean alive = false;
        for (int j = 0; processes != null && j < processes.length(); j++) {
          JSONObject process = processes.optJSONObject(j);
          if (process == null) continue;
          boolean live = process.optBoolean("alive");
          alive |= live;
          TextView line = Ui.text(activity, "进程 " + process.optString("pid") + (live ? " · 仍存活" : " · 已退出"),
              11, live ? Ui.warn : Ui.faint, null);
          LinearLayout.LayoutParams lineSpace = new LinearLayout.LayoutParams(-1, -2);
          lineSpace.topMargin = dp(activity, 3);
          box.addView(line, lineSpace);
        }
        if (task.optBoolean("handoffAvailable")) {
          note(activity, box, "有交接摘要可查看。");
          box.addView(commandButton("查看交接摘要", () -> {
            sheet.dialog.dismiss();
            handoff(workflow, task.optString("taskId"));
          }, false));
        }
        if (alive) {
          Button stop = commandButton("结束身份匹配的旧进程", () -> confirm("结束旧进程？",
              "只结束 PID 和启动身份都匹配的旧进程；已发生的外部操作不会撤销。", () -> {
                sheet.dialog.dismiss();
                control("recovery/stop", workflow, task.optString("taskId"), NativeUi.json("confirmed", true));
                handler.postDelayed(() -> recovery(workflow, task.optString("taskId")), 700);
              }), true);
          box.addView(stop);
        }
        if (task.optBoolean("canReconcile")) {
          box.addView(commandButton("我已核对文件，释放并保留成果", () -> confirm("确认已核对？",
              "确认后旧执行会标为已中断并释放资源，不会自动重新执行；之后可手动重试并继续。", () -> {
                sheet.dialog.dismiss();
                control("reconcile", workflow, task.optString("taskId"), NativeUi.json("confirmed", true));
                handler.postDelayed(() -> recovery(workflow, task.optString("taskId")), 700);
              }), false));
        } else if (alive) {
          note(activity, box, "旧进程仍存活，暂时不能核对。");
        }
        if (task.optString("status").equals("interrupted") || task.optString("status").equals("failed")) {
          box.addView(commandButton("重试并继续本轮", () -> {
            sheet.dialog.dismiss();
            rpc("retry", NativeUi.json("workflowId", workflow, "taskId", task.optString("taskId")),
                done -> rpc("resume", NativeUi.json("workflowId", workflow), again ->
                    NativeUi.toast(activity, "已创建新尝试并继续派发")));
          }, false));
        }
        card.addView(box);
      }
      sheet.footer.addView(NativeUi.primaryButton(activity, "关闭", sheet.dialog::dismiss));
      footerHint(activity, sheet.footer, "恢复操作只影响这一轮，不会删除原项目或历史记录。");
    });
  }

  private void handoff(String workflow, String taskId) {
    rpc("task/handoff", NativeUi.json("workflowId", workflow, "taskId", taskId), r -> {
      JSONObject content = r.optJSONObject("handoff");
      if (content == null) {
        showJson("交接摘要", r);
        return;
      }
      Sheet sheet = page(activity, "交接摘要", "给下一个成员看；不含私有推理和凭据", 0.86);
      if (sheet == null) return;
      section(activity, sheet, "任务");
      LinearLayout target = textCard(sheet.body);
      note(activity, target, "目标 · " + content.optString("goal"));
      note(activity, target, "验收 · " + content.optString("acceptance"));
      section(activity, sheet, "上次执行");
      LinearLayout previous = textCard(sheet.body);
      note(activity, previous, "尝试 · " + content.optString("previousAttemptId", "无记录")
          + " · " + CollaborationStatus.execution(content.optString("previousStatus")));
      if (!content.optString("error").isEmpty())
        note(activity, previous, "错误 · " + trim(content.optString("error"), 1200));
      if (!content.optString("result").isEmpty())
        note(activity, previous, "结果摘要 · " + trim(content.optString("result"), 2400));
      JSONObject verification = content.optJSONObject("verification");
      if (verification != null)
        note(activity, previous, CollaborationStatus.verificationSummary(verification));
      JSONObject artifact = content.optJSONObject("artifact");
      if (artifact != null) {
        StringBuilder evidence = new StringBuilder();
        if (!artifact.optString("patch").isEmpty()) evidencePart(evidence, "补丁已生成");
        if (!artifact.optString("archive").isEmpty()) evidencePart(evidence, "归档已生成");
        if (!artifact.optString("patchSha256").isEmpty()) evidencePart(evidence, shortHash(artifact.optString("patchSha256")));
        if (!artifact.optString("archiveSha256").isEmpty()) evidencePart(evidence, shortHash(artifact.optString("archiveSha256")));
        if (evidence.length() > 0) note(activity, previous, "成果 · " + evidence);
      }
      JSONArray requirements = content.optJSONArray("requirements");
      if (requirements != null && requirements.length() > 0) {
        section(activity, sheet, "必须保留的要求");
        LinearLayout list = textCard(sheet.body);
        for (int i = 0; i < requirements.length(); i++) {
          JSONObject requirement = requirements.optJSONObject(i);
          if (requirement != null) note(activity, list, "· " + requirement.optString("text"));
        }
      }
      if (content.optBoolean("uncertainOperations")) {
        LinearLayout warning = textCard(sheet.body);
        note(activity, warning, "上次执行可能有未确认的外部操作；重试前先核对，不能盲目重放。");
      }
      if (!content.optString("continuation").isEmpty()) {
        section(activity, sheet, "继续方式");
        LinearLayout next = textCard(sheet.body);
        note(activity, next, content.optString("continuation"));
      }
      sheet.footer.addView(NativeUi.primaryButton(activity, "关闭", sheet.dialog::dismiss));
      footerHint(activity, sheet.footer, "改派或重试前先确认这些要求和证据，避免上下文丢失。");
    });
  }

  private static String trim(String value, int max) {
    if (value == null) return "";
    return value.length() <= max ? value : value.substring(0, max) + "…";
  }

  private void showJson(String title, JSONObject value) {
    if (value == null) {
      NativeUi.toast(activity, "没有可用的交接摘要");
      return;
    }
    String text;
    try { text = value.toString(2); } catch (Exception e) { text = value.toString(); }
    TextView body = Ui.text(activity, text, 10.5f, Ui.muted, Ui.mono(activity));
    Ui.pad(body, 20, 8, 20, 8);
    body.setTextIsSelectable(true);
    body.setLineSpacing(dp(activity, 1), 1f);
    ScrollView scroll = new ScrollView(activity);
    scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
    int height = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.55f);
    LinearLayout holder = Ui.col(activity);
    holder.addView(scroll, new LinearLayout.LayoutParams(-1, height));
    new MaterialAlertDialogBuilder(activity).setTitle(title).setView(holder)
        .setPositiveButton("关闭", null).show();
  }

  private RadioButton radio(String text) {
    RadioButton button = new RadioButton(activity);
    button.setText(text);
    button.setTextSize(13.5f);
    button.setTextColor(Ui.text);
    button.setLineSpacing(dp(activity, 2), 1f);
    button.setPadding(dp(activity, 2), dp(activity, 7), dp(activity, 2), dp(activity, 7));
    return button;
  }

  private void message(String workflow, String taskId) {
    LinearLayout box = Ui.col(activity);
    Ui.pad(box, 20, 8, 20, 4);
    note(activity, box, taskId.isEmpty() ? "这条补充要求作用于整轮。" : "这条补充要求只作用于所选任务。");
    box.addView(fieldLabel("补充要求"));
    EditText input = formField(box, "补充要求", "", true);
    note(activity, box, "next_attempt 只保存，等后续尚未启动的尝试读取；不会显示成已发送给运行中的成员。");
    RadioGroup delivery = new RadioGroup(activity);
    Ui.pad(delivery, 2, 4, 2, 2);
    RadioButton next = radio("保存，下次尝试生效");
    next.setChecked(true);
    RadioButton restart = radio("中断当前执行，交接后新尝试");
    delivery.addView(next);
    delivery.addView(restart);
    box.addView(delivery);
    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
        .setTitle("补充要求").setView(box).setNegativeButton("取消", null)
        .setPositiveButton("发送", (d, w) -> {
          String text = input.getText().toString().trim();
          if (text.isEmpty()) { NativeUi.toast(activity, "请填写补充要求"); return; }
          if (text.length() > 20000) { NativeUi.toast(activity, "补充要求最多 20000 字"); return; }
          boolean restartNow = restart.isChecked();
          Runnable send = () -> rpc("message", NativeUi.json("workflowId", workflow, "taskId", taskId,
              "clientRequestId", UUID.randomUUID().toString(), "text", text,
              "delivery", restartNow ? "restart" : "next_attempt",
              "confirmed", restartNow), r -> {
                String notice = r.optString("notice");
                NativeUi.toast(activity, notice.isEmpty() ? "补充要求已保存" : notice);
              });
          if (restartNow) confirm("中断并交接？", "当前成员会被中断，保存成果后以新尝试继续；新尝试计入次数和 Token。", send);
          else send.run();
        });
    builder.show();
  }

  private void policy(String workflow, JSONObject doc) {
    JSONObject budget = doc.optJSONObject("budget");
    JSONObject usage = doc.optJSONObject("budgetUsage");
    JSONObject limits = usage == null ? null : usage.optJSONObject("limits");
    if (budget == null) budget = limits;
    LinearLayout box = Ui.col(activity);
    Ui.pad(box, 20, 8, 20, 12);
    note(activity, box, "次数包括失败、重试、改派、规划和返工；时间包含暂停时间；词元只统计 CLI 已上报值。");
    EditText attempts = formNumber(box, "最多执行次数 1–512",
        budget == null ? 96 : budget.optInt("maxAttempts", 96));
    EditText runtime = formNumber(box, "最长运行秒数 1–86400",
        budget == null ? 3600 : budget.optInt("maxRuntimeSeconds", 3600));
    EditText tokens = formNumber(box, "已上报词元上限 0 为不限",
        budget == null ? 0 : budget.optInt("maxReportedTokens", 0));
    LinearLayout reworkGroup = Ui.group(box);
    JSONObject rework = doc.optJSONObject("rework");
    MaterialSwitch auto = switchRow(activity, reworkGroup, "审核未通过时自动返工",
        "只对结构化审核 approved:false 返工，受次数和预算限制",
        rework != null && rework.optBoolean("auto"), null);
    EditText rounds = formNumber(box, "最多返工轮数 0–5",
        rework == null ? 2 : rework.optInt("maxRounds", 2));

    ScrollView scroll = new ScrollView(activity);
    scroll.setFillViewport(false);
    scroll.setClipToPadding(false);
    scroll.addView(box, new ScrollView.LayoutParams(-1, -2));
    LinearLayout holder = Ui.col(activity);
    int height = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.58f);
    holder.addView(scroll, new LinearLayout.LayoutParams(-1, height));
    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
        .setTitle("策略与预算").setView(holder).setNegativeButton("取消", null)
        .setPositiveButton("保存", (d, w) -> {
          try {
            JSONObject next = new JSONObject()
                .put("maxAttempts", Integer.parseInt(attempts.getText().toString()))
                .put("maxRuntimeSeconds", Integer.parseInt(runtime.getText().toString()))
                .put("maxReportedTokens", Integer.parseInt(tokens.getText().toString()));
            JSONObject reworkValue = new JSONObject().put("auto", auto.isChecked())
                .put("maxRounds", Integer.parseInt(rounds.getText().toString()));
            rpc("policy", NativeUi.json("workflowId", workflow, "revision", doc.opt("revision"),
                "budget", next, "rework", reworkValue), r -> {
              NativeUi.toast(activity, "预算与返工策略已保存");
            });
          } catch (Exception error) {
            NativeUi.toast(activity, "请填写有效的整数");
          }
        });
    builder.show();
  }

  private EditText numberField(String hint, int value) {
    EditText field = NativeUi.field(activity, hint, String.valueOf(value), false);
    field.setInputType(InputType.TYPE_CLASS_NUMBER);
    field.setGravity(Gravity.CENTER_VERTICAL);
    return field;
  }

  private void template(String workflow, JSONObject doc) {
    String[] ids = {"auto", "parallel", "development", "research"};
    String[] labels = {"自动", "并行分工", "开发：实现后审核", "独立研究"};
    String current = doc.optString("template", "auto");
    int selected = 0;
    for (int i = 0; i < ids.length; i++) if (ids[i].equals(current)) selected = i;
    choicePage("协作模板", "模板只影响下一轮提交；当前任务计划不会被改写。", Arrays.asList(labels),
        Arrays.asList("审查职责时先实现后审核，否则并行分工", "所有成员作为独立分工执行",
            "实现成员并行，审核成员等待全部实现", "独立研究任务，可使用汇总"), selected, index ->
            rpc("session/config", NativeUi.json("threadId", id(), "template", ids[index]), r -> {
              host.controls();
              NativeUi.toast(activity, "下一轮模板：" + labels[index]);
            }));
  }

  private void cleanup(String workflow) {
    rpc("cleanup/preview", NativeUi.json("workflowId", workflow), r -> {
      JSONArray workspaces = r.optJSONArray("workspaces");
      StringBuilder text = new StringBuilder();
      for (int i = 0; workspaces != null && i < workspaces.length(); i++) {
        JSONObject item = workspaces.optJSONObject(i);
        if (item == null) continue;
        text.append("· ").append(item.optString("path", item.optString("workspace"))).append('\n');
      }
      if (text.length() == 0) text.append("没有可清理的隔离工作区。");
      new MaterialAlertDialogBuilder(activity).setTitle("清理隔离工作区")
          .setMessage("只删除这些尝试的 worktree / 快照，不删除原项目；补丁、会话、验证和用量会保留。\n\n" + text)
          .setNegativeButton("取消", null)
          .setPositiveButton("确认清理", (d, w) -> rpc("cleanup", NativeUi.json("workflowId", workflow,
              "previewToken", r.optString("previewToken"), "confirmed", true), done ->
              NativeUi.toast(activity, "隔离工作区已清理")))
          .show();
    });
  }

  private void taskConfig(String workflow, JSONObject doc, JSONObject task) {
    JSONArray all = doc.optJSONArray("tasks");
    String[] kinds = {"work", "research", "review", "summary"};
    String[] kindLabels = {"实现", "研究", "审核", "汇总"};
    String currentKind = task.optString("kind", "work");
    int kindIndex = 0;
    for (int i = 0; i < kinds.length; i++) if (kinds[i].equals(currentKind)) kindIndex = i;

    ScrollView scroll = new ScrollView(activity);
    scroll.setFillViewport(false);
    scroll.setClipToPadding(false);
    LinearLayout box = Ui.col(activity);
    Ui.pad(box, 20, 8, 20, 12);
    note(activity, box, "只允许在草稿、暂停或部分失败时编辑；存在执行中或待核对任务时后端会拒绝。已完成任务请用重试创建新尝试。");

    box.addView(fieldLabel("任务类型"));
    Spinner kind = new Spinner(activity);
    ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, kindLabels);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    kind.setAdapter(adapter);
    kind.setSelection(kindIndex);
    LinearLayout kindShell = Ui.row(activity);
    kindShell.setBackground(Ui.round(activity, Ui.cardHigh, 14, 0.8f, Ui.lineSoft));
    Ui.pad(kindShell, 10, 2, 10, 2);
    kindShell.addView(kind, new LinearLayout.LayoutParams(-1, dp(activity, 46)));
    LinearLayout.LayoutParams kindSpace = new LinearLayout.LayoutParams(-1, -2);
    kindSpace.bottomMargin = dp(activity, 8);
    box.addView(kindShell, kindSpace);

    EditText attempts = formNumber(box, "最多执行次数 1–10", task.optInt("maxAttempts", 3));

    box.addView(fieldLabel("依赖任务（可多选）"));
    JSONArray dependencies = task.optJSONArray("dependencies");
    Set<String> chosen = new HashSet<>();
    for (int i = 0; dependencies != null && i < dependencies.length(); i++) chosen.add(dependencies.optString(i));
    List<CheckBox> depChecks = new ArrayList<>();
    LinearLayout depBox = Ui.col(activity);
    depBox.setBackground(Ui.round(activity, Ui.cardHigh, 14, 0.8f, Ui.lineSoft));
    Ui.pad(depBox, 12, 4, 12, 4);
    for (int i = 0; all != null && i < all.length(); i++) {
      JSONObject other = all.optJSONObject(i);
      if (other == null || other.optString("id").equals(task.optString("id"))) continue;
      CheckBox dep = new CheckBox(activity);
      dep.setText(other.optString("title") + " · " + other.optString("id"));
      dep.setTextSize(13.0f);
      dep.setTextColor(Ui.text);
      dep.setChecked(chosen.contains(other.optString("id")));
      dep.setTag(other.optString("id"));
      dep.setPadding(dp(activity, 2), dp(activity, 6), dp(activity, 2), dp(activity, 6));
      dep.setMinHeight(dp(activity, 44));
      LinearLayout.LayoutParams depSpace = new LinearLayout.LayoutParams(-1, -2);
      depSpace.bottomMargin = dp(activity, 1);
      depBox.addView(dep, depSpace);
      depChecks.add(dep);
    }
    if (depChecks.isEmpty()) {
      note(activity, box, "没有其他任务可作为依赖。");
    } else {
      LinearLayout.LayoutParams depBoxSpace = new LinearLayout.LayoutParams(-1, -2);
      depBoxSpace.bottomMargin = dp(activity, 8);
      box.addView(depBox, depBoxSpace);
    }

    box.addView(fieldLabel("验收标准"));
    EditText acceptance = formField(box, "说明什么算完成", task.optString("acceptance"), true);
    acceptance.setMinLines(2);

    JSONObject validation = task.optJSONObject("validation");
    JSONArray outputs = validation == null ? null : validation.optJSONArray("outputs");
    StringBuilder outputText = new StringBuilder();
    for (int i = 0; outputs != null && i < outputs.length(); i++) {
      if (outputText.length() > 0) outputText.append('\n');
      outputText.append(outputs.optString(i));
    }
    box.addView(fieldLabel("验收产物（每行一个工作区相对路径）"));
    EditText outputsField = formField(box, "例如 report.md", outputText.toString(), true);
    outputsField.setMinLines(2);

    JSONArray checks = validation == null ? null : validation.optJSONArray("checks");
    StringBuilder checkText = new StringBuilder();
    for (int i = 0; checks != null && i < checks.length(); i++) {
      JSONArray argv = checks.optJSONArray(i);
      if (argv == null) continue;
      if (checkText.length() > 0) checkText.append('\n');
      for (int j = 0; j < argv.length(); j++) {
        if (j > 0) checkText.append(' ');
        checkText.append(argv.optString(j));
      }
    }
    box.addView(fieldLabel("验证命令（每行一条，空格分词；不解释管道和重定向）"));
    EditText checksField = formField(box, "例如 python3 -m unittest discover -s tests", checkText.toString(), true);
    checksField.setMinLines(2);

    EditText timeout = formNumber(box, "验证超时 1–120 秒",
        validation == null ? 60 : validation.optInt("timeoutSeconds", 60));

    scroll.addView(box, new ScrollView.LayoutParams(-1, -2));
    LinearLayout holder = Ui.col(activity);
    int height = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.72f);
    holder.addView(scroll, new LinearLayout.LayoutParams(-1, height));
    MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
        .setTitle("编辑任务配置").setView(holder).setNegativeButton("取消", null)
        .setPositiveButton("保存", (d, w) -> {
          try {
            int max = Integer.parseInt(attempts.getText().toString());
            if (max < 1 || max > 10) throw new IllegalArgumentException("次数须为 1 到 10");
            JSONArray deps = new JSONArray();
            for (CheckBox dep : depChecks) {
              if (dep.isChecked() && dep.getTag() != null) deps.put((String) dep.getTag());
            }
            JSONArray outputList = new JSONArray();
            for (String line : outputsField.getText().toString().split("[\\n,，]+")) {
              if (!line.trim().isEmpty()) outputList.put(line.trim());
            }
            JSONArray commandList = new JSONArray();
            for (String line : checksField.getText().toString().split("\\n")) {
              if (line.trim().isEmpty()) continue;
              JSONArray argv = new JSONArray();
              for (String part : line.trim().split("\\s+")) argv.put(part);
              if (argv.length() > 0) commandList.put(argv);
            }
            JSONObject next = new JSONObject();
            next.put("kind", kinds[kind.getSelectedItemPosition()]);
            next.put("maxAttempts", max);
            next.put("dependencies", deps);
            next.put("acceptance", acceptance.getText().toString().trim());
            next.put("validation", new JSONObject().put("outputs", outputList)
                .put("checks", commandList).put("timeoutSeconds", Integer.parseInt(timeout.getText().toString())));
            if (kinds[kind.getSelectedItemPosition()].equals("review")) next.put("reviewTargets", deps);
            rpc("task/config", NativeUi.json("workflowId", workflow, "taskId", task.optString("id"),
                "revision", doc.opt("revision"), "config", next), r ->
                NativeUi.toast(activity, "任务配置已保存"));
          } catch (Exception error) {
            NativeUi.toast(activity, "配置无效：" + error.getMessage());
          }
        });
    builder.show();
  }

  private TextView fieldLabel(String text) {
    TextView label = Ui.text(activity, text, 12.0f, Ui.muted, Ui.medium(activity));
    Ui.pad(label, 0, 9, 0, 2);
    return label;
  }

  private static String participantMeta(JSONObject doc, String id) {
    JSONObject person = participant(doc, id);
    if (person == null) return "未分配";
    String model = person.optString("model");
    return memberTitle(person) + (model.isEmpty() ? "" : " · " + model);
  }
  private static JSONObject participant(JSONObject doc, String id) {
    JSONArray people = doc.optJSONArray("participants");
    for (int i = 0; people != null && i < people.length(); i++) {
      JSONObject person = people.optJSONObject(i);
      if (person != null && id.equals(person.optString("id"))) return person;
    }
    return null;
  }
  static String state(String s) {
    switch (s) {
      case "draft": return "待开始";
      case "blocked": return "等待依赖";
      case "queued": return "排队";
      case "starting": return "准备中";
      case "running": return "执行中";
      case "waiting_input": return "等待回答";
      case "stopping": return "停止中";
      case "succeeded": return "已完成";
      case "failed": return "失败";
      case "interrupted": return "已中断";
      case "paused": return "暂停派发";
      case "cancelled": return "已停止";
      case "awaiting_acceptance": return "本轮完成";
      case "completed": return "已确认";
      case "partial_failure": return "部分失败";
      case "needs_reconciliation": return "待核对";
      default: return s;
    }
  }
}
