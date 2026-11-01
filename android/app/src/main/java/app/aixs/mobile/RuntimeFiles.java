package app.aixs.mobile;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
/** Bounded caller-selected diagnostic files. */
final class RuntimeFiles {
  static String read(File file) throws IOException {
    return file.isFile() ? new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8) : "";
  }
}
