package app.aixs.mobile;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import org.json.*;

/** Shared Android widgets for Ai-XS. Every screen is native; none of it renders HTML. */
final class NativeUi {
  // Kept for call sites that read the palette directly.
  static int BG, CARD, TEXT, MUTED, ACCENT;

  private static final WeakHashMap<ViewGroup, LinearLayout> openGroups = new WeakHashMap<>();

  static int dp(Activity a, int value) {
    return Ui.dp(a, value);
  }
  static JSONObject json(Object... values) {
    JSONObject result = new JSONObject();
    try {
      for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
    } catch (JSONException error) {
      throw new IllegalArgumentException(error);
    }
    return result;
  }
  static void palette(Activity a) {
    Ui.init(a);
    BG = Ui.bg;
    CARD = Ui.card;
    TEXT = Ui.text;
    MUTED = Ui.muted;
    ACCENT = Ui.accent;
  }
  static void insets(Activity a, View root) {
    insets(a, root, null);
  }

  /** 底部安全区交给 bottomHost 承担，对话区因此能一直铺到底。 */
  static void insets(Activity a, View root, View bottomHost) {
    palette(a);
    WindowCompat.setDecorFitsSystemWindows(a.getWindow(), false);
    boolean light = (a.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
        != Configuration.UI_MODE_NIGHT_YES;
    WindowCompat.getInsetsController(a.getWindow(), root).setAppearanceLightStatusBars(light);
    WindowCompat.getInsetsController(a.getWindow(), root).setAppearanceLightNavigationBars(light);
    if (android.os.Build.VERSION.SDK_INT >= 29)
      a.getWindow().setNavigationBarContrastEnforced(false);
    ViewCompat.setOnApplyWindowInsetsListener(root, (v, w) -> {
      Insets bars = w.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
      Insets ime = w.getInsets(WindowInsetsCompat.Type.ime());
      int bottom = Math.max(bars.bottom, ime.bottom);
      v.setPadding(bars.left, bars.top, bars.right, bottomHost == null ? bottom : 0);
      if (bottomHost != null) bottomHost.setPadding(0, 0, 0, bottom);
      return WindowInsetsCompat.CONSUMED;
    });
    ViewCompat.requestApplyInsets(root);
  }

  // ---- list rows ---------------------------------------------------------
  /** Consecutive tiles join one grouped surface; any other view breaks the group. */
  static void tile(Activity a, LinearLayout parent, int icon, String title, String subtitle, Runnable action) {
    LinearLayout group = openGroups.get(parent);
    boolean reuse = group != null && group.getParent() == parent
        && parent.indexOfChild(group) == parent.getChildCount() - 1;
    if (!reuse) {
      group = Ui.group(parent);
      openGroups.put(parent, group);
    }
    Ui.rowItem(group, icon, title, subtitle, action);
  }
  static LinearLayout column(Activity a) {
    return Ui.col(a);
  }
  static LinearLayout row(Activity a) {
    return Ui.row(a);
  }
  static TextView text(Activity a, String value, int size, int color) {
    if (size >= 20) {
      TextView t = Ui.display(a, value);
      t.setTextColor(color);
      return t;
    }
    TextView t = Ui.text(a, value, size, color, null);
    t.setLineSpacing(Ui.dp(a, 3), 1f);
    return t;
  }
  static TextView body(Activity a, String value) {
    return Ui.body(a, value);
  }

  // ---- page chrome -------------------------------------------------------
  static LinearLayout page(Activity a, String title, Runnable back) {
    palette(a);
    a.setContentView(R.layout.activity_page);
    LinearLayout root = a.findViewById(R.id.page_root);
    insets(a, root);
    LinearLayout bar = Ui.row(a);
    bar.setMinimumHeight(dp(a, 52));
    bar.setPadding(dp(a, 6), 0, dp(a, 12), 0);
    if (back != null)
      bar.addView(Ui.iconButton(a, R.drawable.ic_back, "返回", back));
    if (title != null && !title.isEmpty()) {
      TextView heading = Ui.text(a, title, 16, TEXT, Ui.medium(a));
      LinearLayout.LayoutParams lp = Ui.weigh(1f);
      lp.leftMargin = dp(a, back == null ? 12 : 4);
      bar.addView(heading, lp);
    }
    root.addView(bar);
    return root;
  }
  static LinearLayout scroll(Activity a, LinearLayout root) {
    ScrollView scroll = new ScrollView(a);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout body = Ui.col(a);
    body.setPadding(dp(a, 20), dp(a, 2), dp(a, 20), dp(a, 28));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    return body;
  }
  /** Large heading that opens a scrolling page. */
  static void heading(Activity a, LinearLayout body, String title, String subtitle) {
    body.addView(Ui.display(a, title));
    if (subtitle != null && !subtitle.isEmpty()) {
      TextView note = Ui.text(a, subtitle, 13.5f, MUTED, null);
      note.setLineSpacing(dp(a, 4), 1f);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.topMargin = dp(a, 6);
      body.addView(note, lp);
    }
    Ui.gap(body, subtitle == null || subtitle.isEmpty() ? 12 : 16);
  }
  static void section(Activity a, LinearLayout body, String label) {
    TextView t = Ui.sectionLabel(a, label);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(a, 12);
    lp.bottomMargin = dp(a, 10);
    lp.leftMargin = dp(a, 4);
    body.addView(t, lp);
  }

  // ---- controls ----------------------------------------------------------
  static MaterialButton primaryButton(Activity a, String label, Runnable action) {
    MaterialButton b = (MaterialButton) a.getLayoutInflater().inflate(R.layout.component_button_primary, null);
    b.setText(label);
    b.setOnClickListener(v -> action.run());
    return b;
  }
  static MaterialButton outlinedButton(Activity a, String label, Runnable action) {
    MaterialButton b = (MaterialButton) a.getLayoutInflater().inflate(R.layout.component_button_outlined, null);
    b.setText(label);
    b.setOnClickListener(v -> action.run());
    return b;
  }
  static MaterialButton textButton(Activity a, String label, Runnable action) {
    MaterialButton b = (MaterialButton) a.getLayoutInflater().inflate(R.layout.component_button_text, null);
    b.setText(label);
    b.setOnClickListener(v -> action.run());
    return b;
  }
  /** Read-only status line: soft surface, no ripple, clearly not a control. */
  static TextView statusCard(Activity a, LinearLayout parent, String value) {
    TextView view = Ui.text(a, value, 13, MUTED, null);
    view.setLineSpacing(dp(a, 4), 1f);
    view.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
    view.setBackground(Ui.round(a, Ui.cardHigh, 14, 0.8f, Ui.lineSoft));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(a, 14);
    parent.addView(view, lp);
    return view;
  }
  static EditText field(Activity a, String hint, String value, boolean multiline) {
    TextInputEditText e = new TextInputEditText(a);
    e.setHint(hint);
    e.setText(value);
    e.setTextSize(15);
    e.setSingleLine(!multiline);
    e.setSaveEnabled(false);
    if (multiline) {
      e.setMinLines(3);
      e.setGravity(Gravity.TOP);
    }
    return e;
  }
  static void addField(Activity a, LinearLayout parent, EditText edit) {
    TextInputLayout box = new TextInputLayout(a);
    box.setHint(edit.getHint());
    edit.setHint(null);
    box.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
    box.setBoxCornerRadii(dp(a, 14), dp(a, 14), dp(a, 14), dp(a, 14));
    box.setBoxStrokeColorStateList(android.content.res.ColorStateList.valueOf(Ui.accent));
    box.setHintTextColor(android.content.res.ColorStateList.valueOf(Ui.muted));
    if ((edit.getInputType() & 0xf0) == 0x80)
      box.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(a, 6);
    lp.bottomMargin = dp(a, 10);
    parent.addView(box, lp);
    box.addView(edit, new LinearLayout.LayoutParams(-1, -2));
  }
  static com.google.android.material.textfield.MaterialAutoCompleteTextView dropdown(
      Activity a, LinearLayout parent, String hint, java.util.List<String> entries, String selected) {
    TextInputLayout box = (TextInputLayout) a.getLayoutInflater().inflate(R.layout.component_dropdown, parent, false);
    box.setHint(hint);
    com.google.android.material.textfield.MaterialAutoCompleteTextView input = box.findViewById(R.id.dropdown_value);
    input.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_dropdown_item_1line, entries));
    input.setText(selected, false);
    parent.addView(box);
    return input;
  }
  static void gap(Activity a, LinearLayout root, int height) {
    Ui.gap(root, height);
  }

  // ---- dialogs -----------------------------------------------------------
  static void prompt(Activity a, String title, String hint, String value, boolean multi, Consumer<String> done) {
    EditText input = field(a, hint, value, multi);
    LinearLayout box = Ui.col(a);
    box.setPadding(dp(a, 18), dp(a, 6), dp(a, 18), dp(a, 6));
    addField(a, box, input);
    androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(a)
                                                     .setTitle(title)
                                                     .setView(box)
                                                     .setNegativeButton("取消", null)
                                                     .setPositiveButton("确定", null)
                                                     .create();
    dialog.setOnShowListener(v -> dialog.getButton(-1).setOnClickListener(w -> {
      String text = input.getText().toString();
      if (text.trim().isEmpty()) {
        input.setError("不能为空");
        return;
      }
      dialog.dismiss();
      done.accept(text);
    }));
    dialog.show();
  }
  static void error(Activity a, String message) {
    if (!a.isFinishing())
      new MaterialAlertDialogBuilder(a)
          .setTitle("操作未完成")
          .setMessage(ErrorMessage.friendly(Diagnostics.redact(a, message)))
          .setNegativeButton("查看日志", (d, w) -> a.startActivity(new Intent(a, RuntimeActivity.class).putExtra("page", "logs")))
          .setPositiveButton("知道了", null)
          .show();
  }
  static void toast(Activity a, String message) {
    Toast.makeText(a, message, Toast.LENGTH_SHORT).show();
  }

  // ---- data helpers ------------------------------------------------------
  static String engineName(String id) {
    return id.equals("claude") ? "Claude Code" : id.equals("deepseek") ? "DeepSeek" : "Codex";
  }
  static void call(Activity a, String method, JSONObject params, Consumer<JSONObject> success) {
    NativeEngine.call(method, params, value -> {
      if (!a.isFinishing())
        try {
          success.accept(value);
        } catch (Exception error) {
          error(a, error.toString());
        }
    }, message -> error(a, message));
  }
  static String pretty(Object value) {
    if (value == null || value == JSONObject.NULL)
      return "";
    try {
      return value instanceof JSONObject ? ((JSONObject) value).toString(2)
          : value instanceof JSONArray   ? ((JSONArray) value).toString(2)
                                          : String.valueOf(value);
    } catch (JSONException error) {
      return String.valueOf(value);
    }
  }

  static MaterialTextView plain(Activity a, String value, float size, int color) {
    MaterialTextView t = new MaterialTextView(a);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setIncludeFontPadding(false);
    return t;
  }
  static Typeface medium(Activity a) {
    return Ui.medium(a);
  }
}
