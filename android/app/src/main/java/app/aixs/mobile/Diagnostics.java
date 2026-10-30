package app.aixs.mobile;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** App-owned logs only. Never collect credentials or URL query strings. */
final class Diagnostics {
  static String redact(String text) {
    return text.replaceAll("(?i)(bearer\\s+|token=|api[_-]?key[=:]\\s*|sk-)[^\\s\"'&]+", "[已隐藏]");
  }
  static String redact(Context context, String text) {
    String safe = redact(text);
    for (String name : new String[] {"device-mcp-token", "runtime/rootfs/opt/aixs/web/.state/token"}) {
      try {
        String secret =
            new String(RuntimeManager.read(new File(context.getFilesDir(), name)), StandardCharsets.UTF_8).trim();
        if (!secret.isEmpty())
          safe = safe.replace(secret, "[已隐藏]");
      } catch (Exception ignored) {
      }
    }
    for (String folder : new String[] {"native", "web"}) try {
        org.json.JSONObject data = RuntimeManager.json(
            new File(context.getFilesDir(), "runtime/rootfs/opt/aixs/" + folder + "/.state/providers.json"));
        safe = hideValues(data, safe, false);
      } catch (Exception ignored) {
      }
    return safe;
  }
  private static String hideValues(org.json.JSONObject object, String text, boolean privateValues) throws Exception {
    java.util.Iterator<String> keys = object.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      Object value = object.get(key);
      boolean secret = privateValues || key.equals("key") || key.equals("headers");
      if (value instanceof org.json.JSONObject)
        text = hideValues((org.json.JSONObject) value, text, secret);
      else if (secret && value instanceof String && !((String) value).isEmpty())
        text = text.replace((String) value, "[已隐藏]");
    }
    return text;
  }
  static synchronized void record(Context context, String message) {
    try {
      File file = new File(context.getFilesDir(), "browser.log");
      if (file.length() > 256 * 1024) {
        File old = new File(context.getFilesDir(), "browser.previous.log");
        old.delete();
        file.renameTo(old);
      }
      try (FileOutputStream out = new FileOutputStream(file, true)) {
        out.write(
            (System.currentTimeMillis() + " " + redact(context, message) + "\n").getBytes(StandardCharsets.UTF_8));
      }
    } catch (IOException ignored) {
    }
  }
  static String tail(Context context, String name) throws IOException {
    File file = new File(context.getFilesDir(), name);
    if (!file.isFile())
      return "暂无记录";
    try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
      long offset = Math.max(0, in.length() - 18000);
      in.seek(offset);
      byte[] bytes = new byte[(int) (in.length() - offset)];
      in.readFully(bytes);
      return redact(context, new String(bytes, StandardCharsets.UTF_8));
    }
  }
}
