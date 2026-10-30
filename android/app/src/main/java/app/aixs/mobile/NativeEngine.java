package app.aixs.mobile;

import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.json.*;

/** In-process Android client of the private Linux service pipes. No URL or WebView. */
final class NativeEngine {
  interface Observer {
    void event(JSONObject value);
  }
  static final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
  private static final ConcurrentHashMap<String, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
  private static final AtomicLong ids = new AtomicLong();
  private static final Handler main = new Handler(Looper.getMainLooper());
  private static final ExecutorService writes = Executors.newSingleThreadExecutor();
  private static volatile Consumer<String> diagnostics = value -> {};
  private static volatile Process process;
  private static volatile BufferedWriter input;
  private static volatile CompletableFuture<Void> readiness = new CompletableFuture<>();
  static boolean ready() {
    return process != null && process.isAlive() && readiness.isDone() && !readiness.isCompletedExceptionally();
  }
  static void attach(Process child, Consumer<String> logger) {
    fail("Agent 服务已重新连接");
    diagnostics = logger;
    process = child;
    readiness = new CompletableFuture<>();
    input = new BufferedWriter(new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8));
    Thread reader = new Thread(() -> {
      try (BufferedReader lines =
               new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = lines.readLine()) != null) {
          if (process != child)
            break;
          JSONObject message;
          try {
            message = new JSONObject(line);
          } catch (JSONException ignored) {
            logger.accept(line);
            continue;
          }
          if (message.optBoolean("ready")) {
            readiness.complete(null);
            publish(new JSONObject().put("type", "native/ready"));
          } else if (message.has("event"))
            publish(message.getJSONObject("event"));
          else {
            CompletableFuture<JSONObject> call = pending.remove(message.optString("id"));
            if (call != null) {
              if (message.has("error"))
                call.completeExceptionally(new IOException(message.optString("error")));
              else
                call.complete(
                    message.optJSONObject("result") == null ? new JSONObject() : message.getJSONObject("result"));
            }
          }
        }
      } catch (Exception error) {
        logger.accept("Agent 通信结束：" + error.getMessage());
      } finally {
        if (process == child) {
          fail("Agent 服务已停止，请在设置中重新启动");
          publish(statusEvent());
        }
      }
    }, "aixs-native-reader");
    reader.setDaemon(true);
    reader.start();
    Thread errors = new Thread(() -> {
      try (BufferedReader lines =
               new BufferedReader(new InputStreamReader(child.getErrorStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = lines.readLine()) != null) logger.accept(line);
      } catch (IOException ignored) {
      }
    }, "aixs-native-errors");
    errors.setDaemon(true);
    errors.start();
  }
  static JSONObject statusEvent() {
    JSONObject value = new JSONObject();
    try {
      value.put("type", "native/stopped");
    } catch (JSONException ignored) {
    }
    return value;
  }
  static void awaitReady() throws Exception {
    readiness.get(60, TimeUnit.SECONDS);
  }
  static CompletableFuture<JSONObject> request(String method, JSONObject params) {
    CompletableFuture<JSONObject> future = new CompletableFuture<>();
    if (!ready()) {
      future.completeExceptionally(new IOException("请先在设置中启动 Agent 服务"));
      return future;
    }
    String id = Long.toString(ids.incrementAndGet());
    Process owner = process;
    pending.put(id, future);
    long began = System.currentTimeMillis();
    diagnostics.accept("RPC #" + id + " " + method + " 开始");
    future.whenComplete((result, error) -> {
      String detail = "RPC #" + id + " " + method + " · " + (System.currentTimeMillis() - began) + "ms · ";
      if (error == null) diagnostics.accept(detail + "完成");
      else {
        StringWriter trace = new StringWriter(); error.printStackTrace(new PrintWriter(trace));
        diagnostics.accept("[ERROR] " + detail + "失败：" + message(error) + "\n" + trace);
      }
    });
    main.postDelayed(() -> {
      if (pending.remove(id, future))
        future.completeExceptionally(new IOException("操作超时：" + method));
    }, 310000);
    writes.execute(() -> {
      try {
        if (!pending.containsKey(id) || owner != process)
          throw new IOException("服务连接已变更，请重试");
        JSONObject packet = new JSONObject().put("id", id).put("method", method).put("params", params);
        BufferedWriter target = input;
        if (target == null)
          throw new IOException("服务连接已关闭");
        target.write(packet.toString());
        target.newLine();
        target.flush();
      } catch (Exception error) {
        if (pending.remove(id, future))
          future.completeExceptionally(error);
      }
    });
    return future;
  }
  static void call(String method, JSONObject params, Consumer<JSONObject> success, Consumer<String> failure) {
    request(method, params).whenComplete((result, error) -> main.post(() -> {
      if (error == null)
        success.accept(result);
      else
        failure.accept(message(error));
    }));
  }
  static String message(Throwable error) {
    while (error.getCause() != null) error = error.getCause();
    return error.getMessage() == null ? error.toString() : error.getMessage();
  }
  static void publish(JSONObject event) {
    main.post(() -> {
      for (Observer observer : observers) observer.event(event);
    });
  }
  static void fail(String reason) {
    readiness.completeExceptionally(new IOException(reason));
    input = null;
    process = null;
    for (CompletableFuture<JSONObject> call : pending.values()) call.completeExceptionally(new IOException(reason));
    pending.clear();
  }
}
