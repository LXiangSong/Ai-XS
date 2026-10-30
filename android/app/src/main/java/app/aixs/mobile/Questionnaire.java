package app.aixs.mobile;

import org.json.*;

/** Keeps every question's draft until the user submits the complete form. */
final class Questionnaire {
  final JSONArray questions;
  private final JSONObject drafts = new JSONObject();
  Questionnaire(JSONArray questions) { this.questions = questions; }
  void save(int index, JSONObject choice) {
    try { drafts.put(String.valueOf(index), new JSONObject(choice.toString())); }
    catch (JSONException error) { throw new IllegalArgumentException(error); }
  }
  JSONObject payload(int index, String id) {
    JSONObject question = questions.optJSONObject(index);
    JSONObject draft = drafts.optJSONObject(String.valueOf(index));
    if (draft == null) draft = new JSONObject();
    try {
      return new JSONObject().put("id", id).put("question", question.optString("question", question.optString("header")))
          .put("options", question.optJSONArray("options")).put("multiSelect", question.optBoolean("multiSelect")).put("allowText", true)
          .put("selected", draft.optJSONArray("selected")).put("text", draft.optString("text")).put("canPrevious", index > 0)
          .put("step", (index + 1) + "/" + questions.length()).put("submitLabel", index + 1 < questions.length() ? "下一步" : "提交全部");
    } catch (JSONException error) { throw new IllegalArgumentException(error); }
  }
  JSONObject result(String engine) {
    JSONObject answers = new JSONObject();
    boolean skipped = false;
    try {
      for (int i = 0; i < questions.length(); i++) {
        JSONObject draft = drafts.optJSONObject(String.valueOf(i));
        if (draft == null) throw new IllegalStateException("还有问题未回答");
        JSONArray values = new JSONArray();
        if (draft.optBoolean("answered")) {
          JSONArray selected = draft.optJSONArray("selected");
          if (selected != null) for (int j = 0; j < selected.length(); j++) values.put(selected.optString(j));
          String text = draft.optString("text").trim();
          if (!text.isEmpty()) values.put(text);
        }
        if (values.length() == 0) { values.put("用户暂不回答"); skipped = true; }
        answers.put(questions.optJSONObject(i).optString("id", "q" + i), new JSONObject().put("answers", values));
      }
      JSONObject result = new JSONObject().put("answers", answers);
      if (skipped && "claude".equals(engine)) result.put("cancelled", true);
      return result;
    } catch (JSONException error) { throw new IllegalArgumentException(error); }
  }
}
