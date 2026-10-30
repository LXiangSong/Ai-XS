package app.aixs.mobile;

import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;

/** Explicit native approval controls shared by all three Agent adapters. */
final class NativeApprovals {
  static void show(Activity a, JSONObject request, Consumer<JSONObject> answer) {
    JSONObject p = request.optJSONObject("params");
    if (p == null)
      p = new JSONObject();
    final JSONObject params = p;
    String method = request.optString("method");
    LinearLayout fields = NativeUi.column(a);
    ScrollView scroll = new ScrollView(a);
    scroll.addView(fields);
    fields.setPadding(NativeUi.dp(a, 14), 0, NativeUi.dp(a, 14), NativeUi.dp(a, 10));
    LinkedHashMap<String, ArrayList<CheckBox>> choices = new LinkedHashMap<>();
    LinkedHashMap<String, EditText> texts = new LinkedHashMap<>();
    LinkedHashMap<String, android.view.View> elicitation = new LinkedHashMap<>();
    JSONObject schema = params.optJSONObject("requestedSchema");
    if (schema == null)
      schema = params.optJSONObject("schema");
    final JSONObject formSchema = schema == null ? new JSONObject() : schema;
    boolean questions = method.equals("item/tool/requestUserInput");
    JSONArray list = params.optJSONArray("questions");
    if (questions && list != null) {
      for (int i = 0; i < list.length(); i++) {
        JSONObject q = list.optJSONObject(i);
        String id = q.optString("id", "q" + i);
        fields.addView(NativeUi.body(a, q.optString("question", q.optString("header"))));
        ArrayList<CheckBox> checks = new ArrayList<>();
        JSONArray options = q.optJSONArray("options");
        if (options != null)
          for (int j = 0; j < options.length(); j++) {
            JSONObject option = options.optJSONObject(j);
            CheckBox check = new com.google.android.material.checkbox.MaterialCheckBox(a);
            check.setText(option.optString("label") + "\n" + option.optString("description"));
            check.setTextColor(NativeUi.TEXT);
            checks.add(check);
            fields.addView(check);
            if (!q.optBoolean("multiSelect"))
              check.setOnCheckedChangeListener((button, on) -> {
                if (on)
                  for (CheckBox other : checks)
                    if (other != button)
                      other.setChecked(false);
              });
          }
        choices.put(id, checks);
        EditText text = NativeUi.field(a, "补充或直接输入回答", "", true);
        texts.put(id, text);
        NativeUi.addField(a, fields, text);
      }
    } else {
      for (ApprovalDetails.Section section : ApprovalDetails.sections(params)) {
        fields.addView(NativeUi.text(a, section.title, 12, NativeUi.MUTED));
        TextView detail = NativeUi.body(a, Diagnostics.redact(a, section.text));
        detail.setTextIsSelectable(true);
        if (section.code) {
          detail.setTypeface(android.graphics.Typeface.MONOSPACE);
          detail.setTextSize(13);
        }
        fields.addView(detail);
      }
      if (method.equals("mcpServer/elicitation/request")) {
        String url = params.optString("url");
        if (!url.isEmpty())
          fields.addView(NativeUi.primaryButton(a, "打开授权页面", () -> {
            Uri uri = Uri.parse(url);
            if (!"https".equals(uri.getScheme())) {
              NativeUi.error(a, "授权页面需要 HTTPS 地址");
              return;
            }
            a.startActivity(new Intent(Intent.ACTION_VIEW, uri));
          }));
        JSONObject properties = formSchema.optJSONObject("properties");
        if (properties != null) {
          Iterator<String> keys = properties.keys();
          while (keys.hasNext()) {
            String key = keys.next();
            JSONObject property = properties.optJSONObject(key);
            if (property == null)
              continue;
            fields.addView(NativeUi.text(a, property.optString("title", key), 14, NativeUi.MUTED));
            android.view.View field;
            if (property.optString("type").equals("boolean")) {
              CheckBox check = new com.google.android.material.checkbox.MaterialCheckBox(a);
              check.setText(property.optString("description", key));
              check.setTextColor(NativeUi.TEXT);
              check.setChecked(property.optBoolean("default"));
              field = check;
            } else if (property.optJSONArray("enum") != null) {
              ArrayList<String> entries = new ArrayList<>();
              JSONArray values = property.optJSONArray("enum");
              for (int i = 0; i < values.length(); i++) entries.add(values.optString(i));
              field = NativeUi.dropdown(a, fields, property.optString("title", key), entries,
                  property.optString("default", entries.isEmpty() ? "" : entries.get(0)));
            } else
              field =
                  NativeUi.field(a, property.optString("description", key), property.optString("default", ""), false);
            elicitation.put(key, field);
            if (field.getParent() == null) {
              if (field instanceof EditText)
                NativeUi.addField(a, fields, (EditText) field);
              else
                fields.addView(field);
            }
          }
        }
      }
    }
    AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
                             .setTitle(questions ? "Agent 需要你的回答" : "允许这次操作？")
                             .setView(scroll)
                             .setCancelable(false)
                             .setNegativeButton(questions ? "暂不回答" : "拒绝", null)
                             .setPositiveButton(questions ? "提交回答" : "允许本次", null)
                             .create();
    dialog.setOnShowListener(v -> {
      for (int button : new int[] {-1, -2})
        dialog.getButton(button).setOnClickListener(w -> {
          boolean accepted = button == -1;
          JSONObject result = NativeUi.json();
          try {
            if (questions) {
              JSONObject answers = new JSONObject();
              for (String id : choices.keySet()) {
                JSONArray values = new JSONArray();
                if (accepted) {
                  for (CheckBox check : choices.get(id))
                    if (check.isChecked())
                      values.put(check.getText().toString().split("\n", 2)[0]);
                  String text = texts.get(id).getText().toString().trim();
                  if (!text.isEmpty())
                    values.put(text);
                  if (values.length() == 0) {
                    texts.get(id).setError("请选择或填写回答");
                    return;
                  }
                } else
                  values.put("用户暂不回答");
                answers.put(id, NativeUi.json("answers", values));
              }
              result.put("answers", answers);
              if (!accepted && params.optString("engine").equals("claude"))
                result.put("cancelled", true);
            } else if (method.equals("item/permissions/requestApproval"))
              result = NativeUi.json(
                  "permissions", accepted ? params.optJSONObject("permissions") : new JSONObject(), "scope", "turn");
            else if (method.equals("execCommandApproval") || method.equals("applyPatchApproval"))
              result = NativeUi.json("decision", accepted ? "approved" : "denied");
            else if (method.equals("mcpServer/elicitation/request")) {
              result = NativeUi.json("action", accepted ? "accept" : "decline");
              if (accepted && !params.optString("mode").equals("url")) {
                JSONObject content = new JSONObject();
                JSONObject props = formSchema.optJSONObject("properties");
                JSONArray required = formSchema.optJSONArray("required");
                for (String key : elicitation.keySet()) {
                  android.view.View field = elicitation.get(key);
                  Object value = field instanceof CheckBox ? ((CheckBox) field).isChecked()
                      : field instanceof com.google.android.material.textfield.MaterialAutoCompleteTextView
                      ? ((EditText) field).getText().toString()
                      : ((EditText) field).getText().toString();
                  if (required != null && value instanceof String && ((String) value).isEmpty()) {
                    for (int i = 0; i < required.length(); i++)
                      if (key.equals(required.optString(i))) {
                        ((EditText) field).setError("必填");
                        return;
                      }
                  }
                  String type = props.optJSONObject(key).optString("type");
                  if (type.equals("integer") || type.equals("number")) {
                    try {
                      value = type.equals("integer") ? Long.parseLong(value.toString())
                                                     : Double.parseDouble(value.toString());
                    } catch (NumberFormatException error) {
                      NativeUi.error(a, "请输入有效数字：" + key);
                      return;
                    }
                  }
                  content.put(key, value);
                }
                result.put("content", content);
              }
            } else if (method.equals("item/tool/call"))
              result = NativeUi.json("success", false, "contentItems",
                  new JSONArray().put(NativeUi.json("type", "inputText", "text", "当前未配置此动态工具的原生处理器")));
            else
              result = NativeUi.json("decision", accepted ? "accept" : "decline");
          } catch (JSONException error) {
            NativeUi.error(a, error.toString());
            return;
          }
          dialog.dismiss();
          answer.accept(result);
        });
    });
    dialog.show();
    // Long commands and edit arguments scroll without pushing decisions off screen.
    if (!questions) {
      int maximum = (int) (a.getResources().getDisplayMetrics().heightPixels * .55f);
      scroll.post(() -> {
        if (scroll.getHeight() > maximum) {
          android.view.ViewGroup.LayoutParams layout = scroll.getLayoutParams();
          layout.height = maximum;
          scroll.setLayoutParams(layout);
        }
      });
    }
  }
}
