package app.aixs.mobile;

import android.app.*;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.util.*;
import org.json.*;

/** Native API, MCP and extension settings; credentials never enter a webpage. */
public final class NativeToolsActivity extends androidx.appcompat.app.AppCompatActivity {
  private String engine = "codex", mode = "thirdparty";
  private String currentPage = "menu", entryPage = "menu";
  private LinearLayout root, body;
  private JSONObject profiles;
  private EditText name, url, key, modelIds, headers;
  private com.google.android.material.textfield.MaterialAutoCompleteTextView protocol;
  private TextView detail;
  private final HashMap<String, JSONObject> drafts = new HashMap<>();
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    engine = getIntent().getStringExtra("engine");
    if (engine == null)
      engine = getSharedPreferences("native-chat", 0).getString("engine", "codex");
    entryPage = getIntent().getStringExtra("page");
    if (entryPage == null) entryPage = "menu";
    getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
      @Override public void handleOnBackPressed() { back(); }
    });
    String target = state == null ? entryPage : state.getString("page", entryPage);
    if ("api".equals(target)) api();
    else if ("extensions".equals(target)) extensions();
    else if ("mcp".equals(target)) mcp();
    else menu();
  }
  private void page(String title) {
    root = NativeUi.page(this, title, this::back);
    body = NativeUi.scroll(this, root);
  }
  private void call(String method, JSONObject params, java.util.function.Consumer<JSONObject> done) {
    try {
      params.put("engine", engine);
    } catch (JSONException ignored) {
    }
    LinearLayout owner = body;
    NativeUi.call(this, method, params, result -> {
      if (!isFinishing() && owner == body) done.accept(result);
    });
  }
  private void chooseAgent(Runnable next) {
    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
        .setTitle("选择 Agent")
        .setItems(new String[] {"Codex", "Claude Code", "DeepSeek"},
            (d, i) -> {
              engine = new String[] {"codex", "claude", "deepseek"}[i];
              drafts.clear();
              next.run();
            })
        .show();
  }
  private void back() {
    if (currentPage.equals(entryPage) || currentPage.equals("menu")) finish();
    else menu();
  }
  @Override protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("page",currentPage);
  }
  private void menu() {
    currentPage = "menu";
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    page("");
    NativeUi.heading(this, body, "工具与扩展", "管理 MCP 连接、Skills 与插件。API、运行环境和手机诊断统一放在设置中。");
    NativeUi.tile(this, body, R.drawable.ic_tools, "共享 MCP 工具", "连接工具服务器，查看可用能力", this::mcp);
    NativeUi.tile(this, body, R.drawable.ic_spark, "Skills 与插件", "GitHub 获取、本地导入与 Agent 分配", this::extensions);
  }
  private void api() {
    currentPage = "api";
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    root = NativeUi.page(this, "API 服务", this::back);
    body = NativeUi.scroll(this, root);
    NativeUi.statusCard(this, body, "正在读取配置…");
    call("provider/read", NativeUi.json(), r -> {
      profiles = r.optJSONObject("profiles");
      mode = r.optString("mode", "thirdparty");
      JSONObject active = profiles == null ? null : profiles.optJSONObject(mode);
      if (active != null && !active.optBoolean("keyConfigured"))
        mode = "thirdparty";
      form(r);
    });
  }

  private void form(JSONObject snapshot) {
    if (body == null) return;
    body.removeAllViews();
    JSONObject source = drafts.containsKey(mode) ? drafts.get(mode)
        : profiles == null ? null : profiles.optJSONObject(mode);
    JSONObject saved = profiles == null ? null : profiles.optJSONObject(mode);
    if (source == null) source = new JSONObject();
    boolean official = mode.equals("official");

    LinearLayout hero = Ui.col(this);
    hero.setBackground(Ui.round(this, Ui.card, 18, 1f, Ui.lineSoft));
    hero.setPadding(dp(18), dp(18), dp(18), dp(16));
    TextView kicker = Ui.sectionLabel(this, "API 服务");
    kicker.setTextColor(Ui.accent);
    hero.addView(kicker);
    LinearLayout top = Ui.row(this);
    top.setGravity(android.view.Gravity.CENTER_VERTICAL);
    LinearLayout words = Ui.col(this);
    TextView title = Ui.text(this, "连接模型服务", 25, Ui.text, Ui.medium(this));
    words.addView(title);
    TextView intro = Ui.text(this, "官方与第三方服务独立保存。\n密钥只留在本机，不进入网页。", 13, Ui.muted, null);
    intro.setLineSpacing(dp(4), 1f);
    LinearLayout.LayoutParams introSpace = new LinearLayout.LayoutParams(-1, -2);
    introSpace.topMargin = dp(7);
    words.addView(intro, introSpace);
    top.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
    TextView agent = Ui.text(this, NativeUi.engineName(engine) + "  ▾", 12, Ui.onAccentSoft, Ui.medium(this));
    agent.setGravity(android.view.Gravity.CENTER);
    agent.setPadding(dp(12), dp(7), dp(12), dp(7));
    agent.setBackground(Ui.round(Ui.accentSoft, dp(999)));
    agent.setOnClickListener(v -> chooseAgent(this::api));
    LinearLayout.LayoutParams agentSpace = new LinearLayout.LayoutParams(-2, -2);
    agentSpace.leftMargin = dp(12);
    top.addView(agent, agentSpace);
    LinearLayout.LayoutParams topSpace = new LinearLayout.LayoutParams(-1, -2);
    topSpace.topMargin = dp(10);
    hero.addView(top, topSpace);
    LinearLayout.LayoutParams heroSpace = new LinearLayout.LayoutParams(-1, -2);
    heroSpace.bottomMargin = dp(18);
    body.addView(hero, heroSpace);

    body.addView(modeTabs(snapshot));

    section("服务详情");
    name = NativeUi.field(this, "服务名称", source.optString("name"), false);
    name.setEnabled(!official);
    NativeUi.addField(this, body, name);
    style(name);

    url = NativeUi.field(this, "API 地址（Base URL）", source.optString("baseUrl"), false);
    url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    url.setEnabled(!official);
    NativeUi.addField(this, body, url);
    style(url);

    JSONArray protocols = snapshot.optJSONArray("protocols");
    ArrayList<String> values = new ArrayList<>();
    if (protocols != null)
      for (int i = 0; i < protocols.length(); i++) values.add(protocols.optString(i));
    protocol = NativeUi.dropdown(this, body, "API 协议", values, source.optString("protocol"));
    style(protocol);
    com.google.android.material.textfield.TextInputLayout protocolBox = inputBox(protocol);
    boolean selectable = !official && values.size() > 1;
    if (protocolBox != null) {
      protocolBox.setEnabled(selectable);
      if (!selectable)
        protocolBox.setEndIconMode(com.google.android.material.textfield.TextInputLayout.END_ICON_NONE);
      protocolBox.setHelperText(official ? "官方 API 的协议固定。"
          : "codex".equals(engine) ? "Codex 使用 OpenAI Responses 协议。"
          : "claude".equals(engine) ? "Claude Code 使用 Anthropic Messages 协议；地址末尾的 /v1 会自动处理。"
          : "选择第三方服务支持的协议。");
    }

    key = NativeUi.field(this,
        saved != null && saved.optBoolean("keyConfigured") ? "已保存，留空保留" : "输入 API Key",
        source.optString("key"), false);
    key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    key.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
    NativeUi.addField(this, body, key);
    style(key);

    section("模型");
    JSONArray ids = source.optJSONArray("models");
    StringBuilder joined = new StringBuilder();
    if (ids != null)
      for (int i = 0; i < ids.length(); i++) {
        if (i > 0) joined.append('\n');
        joined.append(ids.optString(i));
      }
    modelIds = NativeUi.field(this, "模型 ID", joined.toString(), true);
    modelIds.setMinLines(4);
    modelIds.setGravity(android.view.Gravity.TOP);
    NativeUi.addField(this, body, modelIds);
    style(modelIds);
    body.addView(hint("每行一个，第一项默认。读取服务商模型会自动填充。"));

    section("高级");
    headers = NativeUi.field(this, "额外请求头（JSON，可选）",
        source.optJSONObject("headers") == null ? "" : source.optJSONObject("headers").toString(), true);
    headers.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    headers.setMinLines(3);
    headers.setGravity(android.view.Gravity.TOP);
    NativeUi.addField(this, body, headers);
    style(headers);

    detail = Ui.text(this, "官方与第三方配置独立保存。地址变化时需要重新填写密钥。", 13, Ui.muted, null);
    detail.setLineSpacing(dp(4), 1f);
    detail.setPadding(dp(14), dp(12), dp(14), dp(12));
    detail.setBackground(Ui.round(this, Ui.cardHigh, 14, 1f, Ui.lineSoft));
    LinearLayout.LayoutParams detailSpace = new LinearLayout.LayoutParams(-1, -2);
    detailSpace.topMargin = dp(16);
    body.addView(detail, detailSpace);

    Button discover = NativeUi.outlinedButton(this, "读取服务商模型", () -> submit(true));
    LinearLayout.LayoutParams discoverSpace = new LinearLayout.LayoutParams(-1, -2);
    discoverSpace.topMargin = dp(14);
    body.addView(discover, discoverSpace);
    Button save = NativeUi.primaryButton(this, "保存并应用", () -> submit(false));
    LinearLayout.LayoutParams saveSpace = new LinearLayout.LayoutParams(-1, -2);
    saveSpace.topMargin = dp(8);
    body.addView(save, saveSpace);
    LinearLayout.LayoutParams noteSpace = new LinearLayout.LayoutParams(-1, -2);
    noteSpace.topMargin = dp(12);
    body.addView(hint("密钥只保存在本机；下次任务使用新配置。"), noteSpace);
  }

  private LinearLayout modeTabs(JSONObject snapshot) {
    LinearLayout track = Ui.row(this);
    track.setBackground(Ui.round(this, Ui.cardHigh, 999, 1f, Ui.lineSoft));
    track.setPadding(dp(4), dp(4), dp(4), dp(4));
    String[][] options = {{"thirdparty", "第三方 API"}, {"official", "官方 API"}};
    for (String[] option : options) {
      boolean active = option[0].equals(mode);
      TextView tab = Ui.text(this, option[1], 13, active ? Ui.onAccentSoft : Ui.muted,
          active ? Ui.medium(this) : null);
      tab.setGravity(android.view.Gravity.CENTER);
      tab.setBackground(active ? Ui.round(Ui.accentSoft, dp(999)) : null);
      tab.setOnClickListener(v -> switchMode(option[0], snapshot));
      track.addView(tab, new LinearLayout.LayoutParams(0, dp(44), 1f));
    }
    return track;
  }

  private void switchMode(String next, JSONObject snapshot) {
    if (next.equals(mode)) return;
    try {
      drafts.put(mode, payload());
    } catch (JSONException error) {
      NativeUi.error(this, "额外请求头需为有效 JSON");
      return;
    }
    mode = next;
    form(snapshot);
  }

  private void section(String label) {
    TextView view = Ui.text(this, label, 11, Ui.muted, Ui.medium(this));
    view.setAllCaps(true);
    view.setLetterSpacing(0.08f);
    LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2);
    space.topMargin = dp(18);
    space.bottomMargin = dp(6);
    space.leftMargin = dp(4);
    body.addView(view, space);
  }

  private TextView hint(String value) {
    TextView view = Ui.text(this, value, 12, Ui.faint, null);
    view.setLineSpacing(dp(3), 1f);
    view.setPadding(dp(4), 0, dp(4), 0);
    return view;
  }

  private int dp(int value) {
    return NativeUi.dp(this, value);
  }

  private com.google.android.material.textfield.TextInputLayout inputBox(android.view.View view) {
    android.view.ViewParent parent = view.getParent();
    while (parent != null && !(parent instanceof com.google.android.material.textfield.TextInputLayout))
      parent = parent.getParent();
    return parent instanceof com.google.android.material.textfield.TextInputLayout
        ? (com.google.android.material.textfield.TextInputLayout) parent : null;
  }

  private void style(EditText view) {
    view.setTextColor(Ui.text);
    view.setHintTextColor(Ui.faint);
    com.google.android.material.textfield.TextInputLayout box = inputBox(view);
    if (box == null) return;
    box.setBoxBackgroundColor(Ui.cardHigh);
    box.setBoxStrokeColorStateList(new ColorStateList(
        new int[][] {new int[] {android.R.attr.state_focused}, new int[0]},
        new int[] {Ui.accent, Ui.lineSoft}));
    box.setHintTextColor(ColorStateList.valueOf(Ui.muted));
    box.setEndIconTintList(ColorStateList.valueOf(Ui.accent));
  }

  private JSONObject payload() throws JSONException {
    JSONArray ids = new JSONArray();
    for (String value : modelIds.getText().toString().split("\\r?\\n"))
      if (!value.trim().isEmpty())
        ids.put(value.trim());
    JSONObject result = NativeUi.json("mode", mode, "name", name.getText().toString().trim(), "baseUrl",
        url.getText().toString().trim(), "protocol", protocol.getText().toString(), "key", key.getText().toString(),
        "models", ids);
    String custom = headers.getText().toString().trim();
    if (!custom.isEmpty())
      result.put("headers", new JSONObject(custom));
    return result;
  }
  private void submit(boolean discover) {
    try {
      JSONObject value = payload();
      detail.setText(discover ? "正在读取模型…" : "正在保存…");
      call(discover ? "provider/discover" : "provider/save", value, result -> {
        if (discover) {
          JSONArray models = result.optJSONArray("models");
          StringBuilder text = new StringBuilder();
          if (models != null)
            for (int i = 0; i < models.length(); i++) {
              if (i > 0)
                text.append('\n');
              text.append(models.optString(i));
            }
          modelIds.setText(text);
          detail.setText("已读取，保存后使用。此操作未调用推理。");
        } else {
          key.setText("");
          headers.setText("");
          drafts.clear();
          profiles = result.optJSONObject("profiles");
          detail.setText("已保存，下一次任务使用此 API。");
          NativeUi.toast(this, "API 配置已保存");
        }
      });
    } catch (JSONException error) {
      NativeUi.error(this, "请检查额外请求头的 JSON 格式");
    }
  }
  private void mcp() {
    currentPage = "mcp";
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    page("");
    NativeUi.heading(this, body, "共享 MCP 工具", "三个 Agent 共用连接；正在运行的任务会在下次新建或恢复会话时加载变更。");
    body.addView(NativeUi.outlinedButton(this, NativeUi.engineName(engine) + " ▾", () -> chooseAgent(this::mcp)));
    body.addView(NativeUi.primaryButton(this, "添加 HTTP MCP", () -> mcpForm("", new JSONObject())));
    NativeUi.gap(this, body, 8);
    call("mcp/list", NativeUi.json("refresh", true), r -> {
      JSONArray list = r.optJSONArray("data");
      if (list == null)
        return;
      for (int i = 0; i < list.length(); i++) {
        JSONObject entry = list.optJSONObject(i);
        body.addView(NativeUi.text(this, entry.optString("name"), 18, NativeUi.ACCENT));
        body.addView(NativeUi.body(this,
            entry.optString("authStatus") + "\n"
                + (entry.has("error") ? entry.optString("error")
                                      : "工具数量："
                            + (entry.optJSONObject("tools") == null ? 0 : entry.optJSONObject("tools").length()))));
        JSONObject tools = entry.optJSONObject("tools");
        if (tools != null)
          body.addView(NativeUi.outlinedButton(this, "查看工具清单",
              ()
                  -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                      .setTitle(entry.optString("name"))
                      .setMessage(tools.names() == null ? "暂无工具" : tools.names().toString())
                      .setPositiveButton("关闭", null)
                      .show()));
        if (!entry.optString("name").equals("aixs-device"))
          body.addView(NativeUi.outlinedButton(this, "修改连接", () -> call("mcp/config/read", NativeUi.json(), value -> {
            JSONObject config = value.optJSONObject("config");
            JSONObject servers = config == null ? null : config.optJSONObject("mcp_servers");
            mcpForm(entry.optString("name"),
                servers == null ? new JSONObject() : servers.optJSONObject(entry.optString("name")));
          })));
        NativeUi.gap(this, body, 10);
      }
    });
  }
  private void mcpForm(String existing, JSONObject value) {
    if (value == null)
      value = new JSONObject();
    final JSONObject previous = value;
    LinearLayout box = NativeUi.column(this);
    box.setPadding(NativeUi.dp(this, 16), 0, NativeUi.dp(this, 16), NativeUi.dp(this, 10));
    EditText name = NativeUi.field(this, "服务器名称", existing, false),
             address = NativeUi.field(this, "http(s)://服务器/mcp", value.optString("url"), false),
             token = NativeUi.field(this, "Bearer 令牌，留空保留", "", false);
    token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    name.setEnabled(existing.isEmpty());
    NativeUi.addField(this, box, name);
    NativeUi.addField(this, box, address);
    NativeUi.addField(this, box, token);
    AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                             .setTitle("HTTP 服务")
                             .setView(box)
                             .setNegativeButton("取消", null)
                             .setPositiveButton("保存", null)
                             .create();
    dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(w -> {
      try {
        String label = name.getText().toString().trim(), url = address.getText().toString().trim();
        if (!label.matches("[A-Za-z0-9_-]+") || !(url.startsWith("http://") || url.startsWith("https://"))) {
          NativeUi.error(this, "请填写有效名称和 HTTP MCP 地址");
          return;
        }
        JSONObject next = new JSONObject(previous.toString());
        next.put("url", url).put("enabled", true);
        String secret = token.getText().toString();
        if (!secret.isEmpty()) {
          JSONObject h = next.optJSONObject("http_headers");
          if (h == null)
            h = new JSONObject();
          h.put("Authorization", "Bearer " + secret);
          next.put("http_headers", h);
          next.remove("bearer_token_env_var");
        }
        call("mcp/config/write", NativeUi.json("name", label, "value", next), r -> {
          dialog.dismiss();
          call("mcp/reload", NativeUi.json(), updated -> mcp());
        });
      } catch (JSONException error) {
        NativeUi.error(this, error.toString());
      }
    }));
    dialog.show();
  }
  private void extensions() {
    startActivity(new Intent(this, ExtensionsActivity.class));
    if ("extensions".equals(entryPage)) finish();
  }
}
