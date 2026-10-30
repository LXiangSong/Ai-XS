package app.aixs.mobile;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class QuestionnaireTest {
  @Test public void backAndNextPreserveBothDraftsAndSubmissionUsesRevisedChoice() throws Exception {
    Questionnaire form = new Questionnaire(new JSONArray("[{\"id\":\"color\",\"question\":\"配色\"},{\"id\":\"density\",\"question\":\"密度\"}]"));
    form.save(0, new JSONObject("{\"answered\":true,\"selected\":[\"蓝色\"],\"text\":\"保留补充\"}"));
    form.save(1, new JSONObject("{\"answered\":true,\"selected\":[\"紧凑\"],\"text\":\"第二题补充\"}"));
    assertEquals("蓝色", form.payload(0,"test").getJSONArray("selected").getString(0));
    assertEquals("保留补充", form.payload(0,"test").getString("text"));
    assertFalse(form.payload(0,"test").getBoolean("canPrevious"));
    assertEquals("下一步", form.payload(0,"test").getString("submitLabel"));
    form.save(0, new JSONObject("{\"answered\":true,\"selected\":[\"绿色\"],\"text\":\"改过的补充\"}"));
    assertEquals("紧凑", form.payload(1,"test").getJSONArray("selected").getString(0));
    assertEquals("提交全部", form.payload(1,"test").getString("submitLabel"));
    for (String engine : new String[]{"codex","claude","deepseek"}) {
      JSONObject answers = form.result(engine).getJSONObject("answers");
      assertEquals("绿色", answers.getJSONObject("color").getJSONArray("answers").getString(0));
      assertEquals("改过的补充", answers.getJSONObject("color").getJSONArray("answers").getString(1));
      assertEquals("第二题补充", answers.getJSONObject("density").getJSONArray("answers").getString(1));
    }
  }
  @Test public void incompleteFormCannotBeSubmittedAndSkipHasAnExplicitOutcome() throws Exception {
    Questionnaire form = new Questionnaire(new JSONArray("[{\"id\":\"q0\",\"question\":\"选择\"}]"));
    try { form.result("codex"); fail("Must retain incomplete form"); } catch (IllegalStateException expected) {}
    form.save(0, new JSONObject("{\"answered\":false}"));
    assertTrue(form.result("claude").getBoolean("cancelled"));
    assertFalse(form.result("deepseek").has("cancelled"));
  }
}
