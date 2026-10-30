package app.aixs.mobile;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Base64;
import android.widget.*;
import com.google.android.material.button.MaterialButton;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Native project picker, file editor, Git overview and project command console. */
public final class NativeWorkspaceActivity extends androidx.appcompat.app.AppCompatActivity {
  private LinearLayout root, body;
  private String cwd, engine, path, processId = "";
  private TextView output;
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    engine = getIntent().getStringExtra("engine");
    if (engine == null)
      engine = "codex";
    cwd = getIntent().getStringExtra("cwd");
    if (cwd == null)
      cwd = getSharedPreferences("native-chat", 0).getString("cwd", "/root/projects");
    projects();
  }
  private void page(String title) {
    root = NativeUi.page(this, "", this::finish);
    body = NativeUi.scroll(this, root);
    if (title != null && !title.isEmpty())
      NativeUi.heading(this, body, title, null);
  }
  private void call(String method, JSONObject params, java.util.function.Consumer<JSONObject> done) {
    try {
      params.put("engine", engine);
    } catch (JSONException ignored) {
    }
    NativeUi.call(this, method, params, done);
  }
  private void projects() {
    page("");
    NativeUi.heading(this, body, "项目", "为任务选择工作目录，三个 Agent 共用项目文件。");
    body.addView(NativeUi.primaryButton(this, "新建项目",
        ()
            -> NativeUi.prompt(this, "新建项目", "项目名称", "", false,
                name -> call("projects/create", NativeUi.json("name", name), r -> {
                  JSONObject p = r.optJSONObject("project");
                  if (p != null)
                    cwd = p.optString("path");
                  projects();
                }))));
    body.addView(NativeUi.outlinedButton(this, "添加已有目录",
        ()
            -> NativeUi.prompt(this, "添加项目", "运行环境中的绝对路径", "/root/projects/", false,
                value -> call("projects/add", NativeUi.json("path", value), r -> projects()))));
    Ui.gap(body, 6);
    NativeUi.section(this, body, "目录");
    NativeUi.tile(this, body, R.drawable.ic_folder, "浏览当前目录", cwd, () -> files(cwd));
    call("projects/list", NativeUi.json(), result -> {
      JSONArray projects = result.optJSONArray("data");
      if (projects == null)
        return;
      for (int i = 0; i < projects.length(); i++) {
        JSONObject p = projects.optJSONObject(i);
        String location = p.optString("path");
        NativeUi.tile(this, body, R.drawable.ic_folder, p.optString("name"), location, () -> project(location));
      }
    });
  }
  private void project(String value) {
    cwd = value;
    page("");
    NativeUi.heading(this, body, value.substring(value.lastIndexOf('/') + 1), cwd);
    body.addView(NativeUi.primaryButton(this, "在此项目开始对话", () -> {
      getSharedPreferences("native-chat", 0).edit().putString("cwd", cwd).apply();
      finish();
    }));
    NativeUi.section(this, body, "项目工具");
    NativeUi.tile(this, body, R.drawable.ic_folder, "文件", "浏览与编辑项目文件", () -> files(cwd));
    NativeUi.tile(this, body, R.drawable.ic_code, "运行命令", "打开项目命令控制台", this::console);
    NativeUi.tile(this, body, R.drawable.ic_history, "Git 变更", "查看未提交的修改", this::git);
    NativeUi.tile(this, body, R.drawable.ic_settings, "项目要求", "三个 Agent 共用的任务要求", () -> call("state/read", NativeUi.json(), state -> {
      JSONObject requirements = state.optJSONObject("projectRequirements");
      if (requirements == null)
        requirements = new JSONObject();
      final JSONObject all = requirements;
      NativeUi.prompt(this, "项目要求", "三个 Agent 共用", all.optString(cwd), true, text -> {
        try {
          all.put(cwd, text);
        } catch (JSONException ignored) {
        }
        call("state/write", NativeUi.json("projectRequirements", all), r -> NativeUi.toast(this, "已保存项目要求"));
      });
    }));
  }
  private void files(String directory) {
    path = directory;
    page("项目文件");
    body.addView(NativeUi.body(this, directory));
    LinearLayout controls = NativeUi.row(this);
    MaterialButton up = NativeUi.outlinedButton(this, "上级", () -> {
      int at = directory.lastIndexOf('/');
      files(at > 0 ? directory.substring(0, at) : "/");
    });
    MaterialButton newFile =
        NativeUi.outlinedButton(this, "新文件", () -> NativeUi.prompt(this, "新建文件", "文件名称", "", false, name -> {
          if (name.contains("/") || name.equals("..")) {
            NativeUi.error(this, "请输入文件名");
            return;
          }
          String file = directory + "/" + name;
          call("native/createFile", NativeUi.json("path", file), r -> editFile(file));
        }));
    MaterialButton newFolder =
        NativeUi.outlinedButton(this, "新文件夹", () -> NativeUi.prompt(this, "新建文件夹", "文件夹名称", "", false, name -> {
          if (name.contains("/") || name.equals("..")) {
            NativeUi.error(this, "请输入文件夹名称");
            return;
          }
          call("fs/createDirectory", NativeUi.json("path", directory + "/" + name), r -> files(directory));
        }));
    for (MaterialButton b : new MaterialButton[] {up, newFile, newFolder}) {
      b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    }
    controls.addView(up);
    controls.addView(newFile);
    controls.addView(newFolder);
    body.addView(controls);
    NativeUi.gap(this, body, 8);
    call("fs/readDirectory", NativeUi.json("path", directory), r -> {
      JSONArray list = r.optJSONArray("entries");
      if (list == null)
        return;
      ArrayList<JSONObject> sorted = new ArrayList<>();
      for (int i = 0; i < list.length(); i++) sorted.add(list.optJSONObject(i));
      sorted.sort((a, b) -> {
        int folder = Boolean.compare(b.optBoolean("isDirectory"), a.optBoolean("isDirectory"));
        return folder != 0 ? folder : a.optString("fileName").compareToIgnoreCase(b.optString("fileName"));
      });
      for (JSONObject entry : sorted) {
        String name = entry.optString("fileName"), next = directory + (directory.endsWith("/") ? "" : "/") + name;
        boolean isDirectory = entry.optBoolean("isDirectory");
        NativeUi.tile(this, body, isDirectory ? R.drawable.ic_folder : R.drawable.ic_code, name, null, () -> {
          if (isDirectory)
            files(next);
          else
            editFile(next);
        });
      }
    });
  }
  private void editFile(String file) {
    call("fs/readFile", NativeUi.json("path", file), r -> {
      String original = new String(Base64.decode(r.optString("dataBase64"), Base64.DEFAULT), StandardCharsets.UTF_8);
      page(file.substring(file.lastIndexOf('/') + 1));
      EditText editor = NativeUi.field(this, "文件内容", original, true);
      editor.setTypeface(Typeface.MONOSPACE);
      editor.setMinLines(15);
      NativeUi.addField(this, body, editor);
      body.addView(NativeUi.primaryButton(this, "保存修改", () -> call("fs/readFile", NativeUi.json("path", file), latest -> {
        String current =
            new String(Base64.decode(latest.optString("dataBase64"), Base64.DEFAULT), StandardCharsets.UTF_8);
        if (!current.equals(original)) {
          NativeUi.error(this, "文件已被 Agent 或其他任务修改，请重新打开后再编辑。");
          return;
        }
        String encoded =
            Base64.encodeToString(editor.getText().toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        call("fs/writeFile", NativeUi.json("path", file, "dataBase64", encoded), saved -> {
          NativeUi.toast(this, "已保存");
          files(path);
        });
      })));
    });
  }
  private void console() {
    page("项目命令");
    body.addView(NativeUi.body(this, cwd));
    EditText command = NativeUi.field(this, "输入 shell 命令", "", true);
    NativeUi.addField(this, body, command);
    output = NativeUi.body(this, "");
    output.setTypeface(Typeface.MONOSPACE);
    MaterialButton run = NativeUi.primaryButton(this, "执行", () -> {
      String value = command.getText().toString().trim();
      if (value.isEmpty() || !processId.isEmpty())
        return;
      processId = UUID.randomUUID().toString();
      output.setText("正在执行…");
      call("command/exec",
          NativeUi.json("command", new JSONArray(Arrays.asList("/bin/sh", "-c", value)), "cwd", cwd, "processId",
              processId, "timeoutMs", 300000),
          result -> {
            processId = "";
            output.setText(
                result.optString("stdout") + result.optString("stderr") + "\n退出码：" + result.optInt("exitCode"));
          });
    });
    body.addView(run);
    body.addView(NativeUi.outlinedButton(this, "停止命令", () -> {
      if (!processId.isEmpty())
        call("command/exec/terminate", NativeUi.json("processId", processId), r -> NativeUi.toast(this, "已请求停止"));
    }));
    body.addView(output);
  }
  private void git() {
    page("Git 变更");
    body.addView(NativeUi.body(this, cwd));
    TextView result = NativeUi.body(this, "读取变更…");
    result.setTypeface(Typeface.MONOSPACE);
    body.addView(result);
    call("command/exec",
        NativeUi.json("command", new JSONArray(Arrays.asList("git", "status", "--short", "--branch")), "cwd", cwd,
            "timeoutMs", 30000),
        r -> result.setText(r.optString("stdout") + r.optString("stderr")));
    LinearLayout diffRow = NativeUi.row(this);
    MaterialButton textDiff = NativeUi.outlinedButton(this, "查看文本差异",
        ()
            -> call("command/exec",
                NativeUi.json("command", new JSONArray(Arrays.asList("git", "diff", "--no-ext-diff")), "cwd", cwd,
                    "timeoutMs", 30000),
                r -> result.setText(r.optString("stdout") + r.optString("stderr"))));
    MaterialButton stagedDiff = NativeUi.outlinedButton(this, "查看已暂存差异",
        ()
            -> call("command/exec",
                NativeUi.json("command", new JSONArray(Arrays.asList("git", "diff", "--cached", "--no-ext-diff")),
                    "cwd", cwd, "timeoutMs", 30000),
                r -> result.setText(r.optString("stdout") + r.optString("stderr"))));
    textDiff.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    stagedDiff.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    diffRow.addView(textDiff);
    diffRow.addView(stagedDiff);
    body.addView(diffRow);
    body.addView(NativeUi.textButton(this, "打开项目命令", this::console));
  }
}
