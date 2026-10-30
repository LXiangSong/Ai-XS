package app.aixs.mobile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;

/** Bounded, line-aligned snapshots; full logs stay on disk. */
final class RuntimeLogText {
  static String read(File path, int limit) throws IOException {
    if (!path.isFile()) return "";
    try (RandomAccessFile file = new RandomAccessFile(path, "r")) {
      long length = file.length(), start = Math.max(0, length - limit);
      file.seek(start);
      byte[] data = new byte[(int) (length - start)];
      file.readFully(data);
      int offset = 0;
      if (start > 0) {
        while (offset < data.length && data[offset] != '\n') offset++;
        if (offset < data.length) offset++;
        else {
          offset = 0;
          while (offset < data.length && (data[offset] & 0xc0) == 0x80) offset++;
        }
      }
      return new String(data, offset, data.length - offset, StandardCharsets.UTF_8)
          .replaceAll("\u001b\\[[0-?]*[ -/]*[@-~]", "").replace('\r', '\n');
    }
  }

  static String filter(String source, String level, String query) {
    return filter(source, level, query, "all", "");
  }

  static String filter(String source, String level, String query, String origin, String operation) {
    String needle = query.trim().toLowerCase(Locale.ROOT);
    StringBuilder out = new StringBuilder();
    java.text.SimpleDateFormat date = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT);
    for (String line : source.split("\n")) {
      if (line.isEmpty()) continue;
      String severity = "", shown = line;
      try {
        JSONObject entry = new JSONObject(line);
        if (entry.has("level") && entry.has("message")) {
          severity = entry.getString("level").toUpperCase(Locale.ROOT);
          if (!origin.equals("all") && !origin.equals(entry.optString("source"))) continue;
          if (!operation.isEmpty() && !operation.equals(entry.optString("operation"))) continue;
          String id = entry.optString("operation");
          shown = date.format(new java.util.Date(entry.optLong("time"))) + " [" + severity + "] "
              + entry.optString("source") + (id.isEmpty() ? "" : " · #" + id) + " · v" + entry.optString("version")
              + "\n  " + entry.optString("message").replace("\n", "\n  ");
        } else if (!operation.isEmpty() || !origin.equals("all")) continue;
      } catch (Exception ignored) {
        if (!operation.isEmpty() || !origin.equals("all")) continue;
      }
      String lower = shown.toLowerCase(Locale.ROOT);
      boolean error = severity.isEmpty() ? lower.contains("error") || lower.contains("fail")
          || line.contains("错误") || line.contains("失败") || line.contains("异常") : severity.equals("ERROR");
      boolean warning = severity.isEmpty() ? lower.contains("warn") || line.contains("警告") : severity.equals("WARN");
      if ("error".equals(level) && !error) continue;
      if ("warn".equals(level) && !error && !warning) continue;
      if (!needle.isEmpty() && !lower.contains(needle)) continue;
      out.append(shown).append('\n');
    }
    return out.toString();
  }

  static int lines(String text) {
    if (text.isEmpty()) return 0;
    int count = text.endsWith("\n") ? 0 : 1;
    for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
    return count;
  }
}
