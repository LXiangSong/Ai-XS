package app.aixs.mobile;

import android.os.Handler;
import android.os.Looper;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Bridge between the {@code ask_user} MCP tool and the conversation UI.
 *
 * <p>The Agent blocks on a latch while the user picks an option in the chat; the answer is then
 * returned as the tool result, so the model continues with a real choice instead of guessing.
 */
final class AskQueue {
  interface Listener {
    void onAsk(JSONObject ask);
  }

  private static final Map<String, Pending> pending = new LinkedHashMap<>();
  private static final Handler main = new Handler(Looper.getMainLooper());
  private static Listener listener;

  static final class Pending {
    final JSONObject ask;
    final CountDownLatch latch = new CountDownLatch(1);
    volatile JSONObject answer;

    Pending(JSONObject ask) {
      this.ask = ask;
    }
  }

  private AskQueue() {}

  /** Registers the conversation screen; any question already waiting is delivered immediately. */
  static void listen(Listener value) {
    listener = value;
    if (value == null) return;
    for (Pending item : snapshot()) value.onAsk(item.ask);
  }

  static void unlisten(Listener value) {
    if (listener == value) listener = null;
  }

  static synchronized List<Pending> snapshot() {
    return new ArrayList<>(pending.values());
  }

  /** Blocks the calling tool thread until the user answers or the timeout expires. */
  static JSONObject ask(JSONObject payload, long timeoutMillis) throws InterruptedException {
    String id = UUID.randomUUID().toString();
    JSONObject ask = new JSONObject();
    try {
      ask.put("id", id).put("question", payload.optString("question"))
          .put("options", payload.optJSONArray("options") == null ? new org.json.JSONArray()
                                                                  : payload.optJSONArray("options"))
          .put("multiSelect", payload.optBoolean("multiSelect", false))
          .put("allowText", payload.optBoolean("allowText", true));
      if (payload.optJSONObject("execution") != null) ask.put("execution", payload.getJSONObject("execution"));
      if (payload.optJSONArray("questions") != null) ask.put("questions", payload.optJSONArray("questions"));
    } catch (Exception ignored) {
    }
    Pending item = new Pending(ask);
    synchronized (AskQueue.class) {
      pending.put(id, item);
    }
    deliver(ask);
    boolean answered;
    try {
      answered = item.latch.await(timeoutMillis, TimeUnit.MILLISECONDS);
    } finally {
      synchronized (AskQueue.class) { pending.remove(id); }
      try { deliver(new JSONObject().put("id", id).put("resolved", true)); }
      catch (Exception ignored) {}
    }
    if (!answered || item.answer == null) {
      JSONObject expired = new JSONObject();
      try {
        expired.put("answered", false).put("reason", "用户未在规定时间内选择");
      } catch (Exception ignored) {
      }
      return expired;
    }
    return item.answer;
  }

  private static void deliver(JSONObject ask) {
    Listener target = listener;
    if (target == null) return;
    main.post(() -> {
      Listener current = listener;
      if (current != null) current.onAsk(ask);
    });
  }

  /** Called from the UI once the user submits; returns false when the question already expired. */
  static boolean answer(String id, JSONObject value) {
    Pending item;
    synchronized (AskQueue.class) {
      item = pending.get(id);
    }
    if (item == null) return false;
    item.answer = value;
    item.latch.countDown();
    return true;
  }
  static void cancelExecution(String attemptId) {
    for (Pending item : snapshot()) {
      JSONObject context=item.ask.optJSONObject("execution");
      if (context!=null && attemptId.equals(context.optString("attemptId")))
        answer(item.ask.optString("id"), NativeUi.json("answered", false, "reason", "执行实例已结束"));
    }
  }
  static void cancelAll() {
    cancel(false);
  }
  static void cancelOrdinary() {
    cancel(true);
  }
  private static void cancel(boolean ordinaryOnly) {
    for (Pending item : snapshot()) {
      if (ordinaryOnly && item.ask.optJSONObject("execution") != null) continue;
      JSONObject cancelled = new JSONObject();
      try { cancelled.put("answered", false).put("reason", "用户停止了任务"); }
      catch (Exception ignored) {}
      answer(item.ask.optString("id"), cancelled);
    }
  }
}
