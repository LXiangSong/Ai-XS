package app.aixs.mobile;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Two bounded, redacted JSONL streams. Ordinary events describe outcomes; Debug explains them. */
final class AppLogs {
  static File file(Context context, boolean debug) {
    return new File(context.getFilesDir(), debug ? "debug.jsonl" : "events.jsonl");
  }
  static synchronized void write(Context context, boolean debug, String level, String source, String operation, String message) {
    try {
      String safe = Diagnostics.redact(context, message == null ? "" : message);
      safe = safe.replaceAll("\u001b\\[[0-?]*[ -/]*[@-~]", "").replace('\r', '\n');
      if (safe.length() > 24000) safe = safe.substring(0, 24000) + "\n[内容过长，已截断]";
      JSONObject entry = new JSONObject().put("time", System.currentTimeMillis()).put("level", level)
          .put("source", source).put("operation", operation == null ? "" : operation)
          .put("version", AppUpdater.version(context)).put("message", safe);
      File target = file(context, debug);
      if (target.length() >= (debug ? 1024 : 512) * 1024) {
        File previous = new File(context.getFilesDir(), debug ? "debug.previous.jsonl" : "events.previous.jsonl");
        if (previous.exists() && !previous.delete()) return;
        if (!target.renameTo(previous)) return;
      }
      try (FileOutputStream out = new FileOutputStream(target, true)) {
        out.write((entry + "\n").getBytes(StandardCharsets.UTF_8));
      }
    } catch (Exception ignored) { /* Logging must never break an operation. */ }
  }
  static void info(Context context, String source, String message) { write(context, false, "INFO", source, "", message); }
  static void failure(Context context, String source, String operation, String message, Throwable error) {
    write(context, false, "ERROR", source, operation, message);
    StringWriter trace = new StringWriter();
    error.printStackTrace(new PrintWriter(trace));
    write(context, true, "ERROR", source, operation, message + "\n" + trace);
  }
  static String tail(Context context, boolean debug, String source, String operation) throws IOException {
    return tail(context, debug, source, operation, "all", "");
  }
  static String tail(Context context, boolean debug, String source, String operation, String level, String query) throws IOException {
    return Diagnostics.redact(context, RuntimeLogText.filter(RuntimeLogText.read(file(context, debug), 64 * 1024),
        level, query, source, operation));
  }
  static String report(Context context) throws Exception {
    StringBuilder out = new StringBuilder("Ai-XS 诊断报告\n");
    out.append("版本：").append(AppUpdater.version(context)).append("\nAndroid：").append(Build.VERSION.RELEASE)
        .append(" / API ").append(Build.VERSION.SDK_INT).append("\n设备：").append(Build.MANUFACTURER)
        .append(' ').append(Build.MODEL).append("\nABI：").append(String.join(", ", Build.SUPPORTED_ABIS)).append('\n');
    RuntimeManager manager = new RuntimeManager(context, s -> {});
    for (String agent : new String[] {"codex", "claude", "deepseek"}) out.append(agent).append(": ").append(manager.installed(agent)).append('\n');
    File state = new File(context.getFilesDir(), "runtime-operation.json");
    if (state.isFile()) out.append("\n最近操作\n").append(RuntimeManager.json(state).toString(2)).append('\n');
    for (String name : new String[] {"events.previous.jsonl", "events.jsonl", "debug.previous.jsonl", "debug.jsonl", "runtime.log", "browser.previous.log", "browser.log"}) {
      File path = new File(context.getFilesDir(), name);
      if (path.isFile()) out.append("\n--- ").append(name).append("（最多 1 MB）---\n")
          .append(RuntimeLogText.filter(RuntimeLogText.read(path, 1024 * 1024), "all", ""));
    }
    return Diagnostics.redact(context, out.toString());
  }
}
