package app.aixs.mobile;

import android.app.*;
import android.content.Intent;
import android.os.*;
import java.io.*;
import java.lang.Process;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

public final class RuntimeService extends Service {
  private final ExecutorService jobs = Executors.newSingleThreadExecutor();
  private final AtomicBoolean busy = new AtomicBoolean(false);
  private JSONObject operation = new JSONObject();
  private RuntimeManager manager;
  private Process backend;
  private DeviceMcpServer device;
  private PowerManager.WakeLock wake;
  private final Handler resourceHandler = new Handler(Looper.getMainLooper());
  private final Runnable resourceSample = new Runnable() {
    public void run() {
      if (NativeEngine.ready()) {
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(memory);
        int thermal = Build.VERSION.SDK_INT >= 29 ? ((PowerManager) getSystemService(POWER_SERVICE)).getCurrentThermalStatus() : -1;
        NativeEngine.call("native/collaboration/resources", NativeUi.json("available", memory.availMem,
            "lowMemory", memory.lowMemory, "thermal", thermal), result -> {
          org.json.JSONArray active=result.optJSONArray("activeAttemptIds");
          if(active!=null) for(AskQueue.Pending pending:AskQueue.snapshot()) {
            JSONObject execution=pending.ask.optJSONObject("execution");if(execution==null)continue;
            String attempt=execution.optString("attemptId");boolean found=false;
            for(int i=0;i<active.length();i++) if(attempt.equals(active.optString(i)))found=true;
            if(!found)AskQueue.cancelExecution(attempt);
          }
          Intent screen=new Intent(RuntimeService.this,CollaborationActivity.class);
          Notification n=new Notification.Builder(RuntimeService.this,"runtime").setSmallIcon(R.drawable.ic_launcher)
              .setContentTitle("Ai-XS · 运行 "+result.optInt("running")+" / "+result.optInt("parallel",2))
              .setContentText("待回答 "+AskQueue.snapshot().size()+" · 点击查看协作")
              .setContentIntent(PendingIntent.getActivity(RuntimeService.this,7,screen,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE))
              .setOngoing(true).build();
          RuntimeService.this.startForeground(61,n);
        }, error -> {});
      }
      resourceHandler.postDelayed(this, 5000);
    }
  };
  @Override
  public void onCreate() {
    super.onCreate();
    resourceHandler.post(resourceSample);
    device = new DeviceMcpServer(this);
    try {
      device.start();
    } catch (Exception e) {
      AppLogs.failure(this, "device", "", "手机工具启动失败", e);
    }
    manager = new RuntimeManager(this, this::log, this::debug);
    NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    notifications.createNotificationChannel(
        new NotificationChannel("runtime", "Ai-XS 运行环境", NotificationManager.IMPORTANCE_LOW));
    Intent screen = new Intent(this, RuntimeActivity.class);
    Notification n = new Notification.Builder(this, "runtime")
                         .setSmallIcon(R.drawable.ic_launcher)
                         .setContentTitle("Ai-XS")
                         .setContentText("运行环境与任务管理")
                         .setContentIntent(PendingIntent.getActivity(
                             this, 0, screen, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                         .setOngoing(true)
                         .build();
    startForeground(61, n);
    wake = ((PowerManager) getSystemService(POWER_SERVICE))
               .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiXS:RuntimeInstall");
    try {
      File state = new File(getFilesDir(), "runtime-operation.json");
      if (state.isFile() && RuntimeManager.json(state).optString("status").equals("running")) {
        operation = RuntimeManager.json(state);
        state("interrupted", "上次操作被系统中断，可在设置中重试");
        AppLogs.write(this, false, "WARN", "runtime", operation.optString("id"), "上次操作被系统中断");
      }
    } catch (Exception ignored) {
    }
  }
  private synchronized void state(String status, String message) {
    try {
      operation = status.equals("running") ? OperationState.step(operation, Diagnostics.redact(this, message), System.currentTimeMillis())
          : OperationState.finish(operation, status, Diagnostics.redact(this, message), System.currentTimeMillis());
      RuntimeManager.write(new File(getFilesDir(), "runtime-operation.json"), operation.toString());
    } catch (Exception ignored) {}
  }
  private void log(String value) {
    AppLogs.write(this, false, "INFO", "runtime", operation.optString("id"), value);
    AppLogs.write(this, true, "INFO", "runtime", operation.optString("id"), value);
    if (busy.get()) state("running", value);
  }
  private void debug(String value) {
    AppLogs.write(this, true, "DEBUG", "runtime", operation.optString("id"), value);
  }
  @Override
  public int onStartCommand(Intent intent, int flags, int id) {
    if (intent == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    String action = intent.getStringExtra("operation");
    if (!busy.compareAndSet(false, true)) {
      AppLogs.write(this, false, "WARN", "runtime", "", "已有环境操作正在进行，请等待完成。");
      return START_NOT_STICKY;
    }
    try {
      String opId = intent.getStringExtra("operationId");
      operation = OperationState.start(opId == null ? java.util.UUID.randomUUID().toString().substring(0, 8) : opId,
          action, intent.getStringExtra("agent"), intent.getStringExtra("origin") == null ? "settings" : intent.getStringExtra("origin"),
          label(action), System.currentTimeMillis());
    } catch (Exception ignored) {}
    log(label(action) + (intent.getStringExtra("agent") == null ? "" : " · " + intent.getStringExtra("agent")));
    jobs.execute(() -> {
      boolean restartAfterUpdate = false;
      String failure = null;
      try {
        wake.acquire(30 * 60 * 1000L);
        boolean agentVersionOperation = "agent-update".equals(action) || "agent-select".equals(action) || "agent-remove".equals(action);
        if (agentVersionOperation) {
          if (backend != null && backend.isAlive()) {
            NativeEngine.request("native/maintenance/prepare", new JSONObject()).get(15, TimeUnit.SECONDS);
            restartAfterUpdate = true;
            NativeEngine.fail("正在更新 Agent，完成后会恢复服务");
            manager.stopBackend();
            backend.destroy();
            if (!backend.waitFor(5, TimeUnit.SECONDS)) backend.destroyForcibly();
            backend = null;
          }
        }
        if (!"start".equals(action) && !"stop".equals(action) && !"check".equals(action) && !"device-check".equals(action)
            && !"agent-check".equals(action) && !"agents-start".equals(action) && !"agents-stop".equals(action) && backend != null && backend.isAlive())
          throw new IOException("请先在 Agent 管理中全部停止，再安装或修复环境");
        if ("stop".equals(action)) {
          NativeEngine.fail("Agent 服务已停止");
          manager.stopBackend();
          if (backend != null) {
            backend.destroy();
            backend.waitFor(5, TimeUnit.SECONDS);
            backend = null;
          }
          log("Agent 服务已停止，数据已保留");
        } else if ("agents-start".equals(action) || "agents-stop".equals(action)) {
          controlAgents(intent, action.equals("agents-start") ? "start" : "stop");
        } else if ("initialize".equals(action))
          manager.initialize();
        else if ("check".equals(action))
          manager.check();
        else if ("device-check".equals(action))
          checkDeviceTools();
        else if ("agent-check".equals(action)) {
          startBackend();
          manager.checkAgentInterfaces();
        }
        else if ("agent".equals(action))
          manager.installAgent(intent.getStringExtra("agent"));
        else if ("agent-update".equals(action))
          manager.updateAgent(intent.getStringExtra("agent"), intent.getStringExtra("version"));
        else if ("agent-select".equals(action) || "agent-remove".equals(action))
          manager.manageAgentVersion(action.equals("agent-select") ? "select" : "remove", intent.getStringExtra("agent"), intent.getStringExtra("release"));
        else if ("import".equals(action))
          manager.importAgent(intent.getStringExtra("agent"), intent.getStringExtra("uri"),
              intent.getStringExtra("url"), intent.getStringExtra("sha256"));
        else if ("start".equals(action))
          startBackend();
        else
          throw new IOException("未知操作");

      } catch (Exception error) {
        failure = error instanceof IOException && error.getMessage() != null ? error.getMessage() : NativeEngine.message(error);
        AppLogs.failure(this, "runtime", operation.optString("id"), "操作失败：" + failure, error);
      } finally {
        if (restartAfterUpdate) {
          try { startBackend(); log("Agent 更新操作结束，服务已恢复。"); }
          catch (Exception error) {
            AppLogs.failure(this, "runtime", operation.optString("id"), "服务恢复失败", error);
            failure = (failure == null ? "" : failure + "；") + "服务恢复失败，请重启服务：" + NativeEngine.message(error);
          }
        }
        state(failure == null ? "completed" : "failed", failure == null ? label(action) + "已完成" : failure);
        AppLogs.write(this, false, failure == null ? "INFO" : "ERROR", "runtime", operation.optString("id"),
            operation.optString("message") + " · 耗时 " + (System.currentTimeMillis() - operation.optLong("startedAt")) + "ms");
        if (wake.isHeld())
          wake.release();
        busy.set(false);
        if (backend == null || !backend.isAlive()) {
          stopForeground(STOP_FOREGROUND_REMOVE);
          stopSelf();
        }
      }
    });
    return START_NOT_STICKY;
  }
  private String label(String action) {
    if ("agents-start".equals(action)) return "启动 Agent";
    if ("agents-stop".equals(action)) return "停止 Agent";
    if ("initialize".equals(action))
      return "初始化 / 修复共享环境";
    if ("check".equals(action))
      return "检查运行环境";
    if ("start".equals(action))
      return "启动 Agent 服务";
    if ("stop".equals(action))
      return "停止 Agent 服务";
    if ("device-check".equals(action))
      return "检查手机工具";
    if ("agent-check".equals(action))
      return "检查 Agent 接口";
    if ("agent-update".equals(action))
      return "更新 Agent";
    if ("agent-select".equals(action))
      return "切换 Agent 版本";
    if ("agent-remove".equals(action))
      return "删除 Agent 旧版本";
    return "安装 Agent";
  }
  private void controlAgents(Intent intent, String action) throws Exception {
    String target = intent.getStringExtra("agent");
    if (target == null) target = "all";
    if (!java.util.Arrays.asList("all", "codex", "claude", "deepseek").contains(target)) throw new IOException("未知 Agent");
    org.json.JSONArray targets = new org.json.JSONArray();
    for (String engine : new String[] {"codex", "claude", "deepseek"}) {
      if (!target.equals("all") && !target.equals(engine)) continue;
      if (action.equals("start") && manager.installed(engine).equals("未安装")) {
        if (!target.equals("all")) throw new IOException("请先在 Agent 管理中安装 " + engine);
        log(engine + " 未安装，已跳过"); continue;
      }
      targets.put(engine);
    }
    if (targets.length() == 0) throw new IOException("尚未安装 Agent，请先在 Agent 管理中安装");
    startBackend();
    log((action.equals("start") ? "正在启动 " : "正在停止 ") + (target.equals("all") ? "全部 Agent" : target));
    JSONObject result = NativeEngine.request("native/agents/control", new JSONObject().put("action", action)
        .put("target", target).put("targets", targets).put("confirmed", intent.getBooleanExtra("confirmed", false)))
        .get(160, TimeUnit.SECONDS);
    org.json.JSONArray entries = result.getJSONArray("data");
    java.util.ArrayList<String> failures = new java.util.ArrayList<>();
    for (int i = 0; i < entries.length(); i++) {
      JSONObject entry = entries.getJSONObject(i);
      String message = entry.optString("agent") + " · " + entry.optString("message");
      log(message);
      debug(entry.toString());
      if (!entry.optString("status").equals("completed")) failures.add(message);
    }
    if (!failures.isEmpty()) throw new IOException(String.join("；", failures));
    if (action.equals("stop") && target.equals("all")) {
      NativeEngine.fail("全部 Agent 已停止，请在 Agent 管理中启动");
      backend.destroy();
      if (!backend.waitFor(8, TimeUnit.SECONDS)) { backend.destroyForcibly(); backend.waitFor(3, TimeUnit.SECONDS); }
      backend = null;
      log("全部 Agent 与共享服务已停止，项目、会话和 API 配置已保留");
    }
  }
  private void checkDeviceTools() throws Exception {
    for (String method : new String[] {"initialize", "tools/list", "tools/call", "phone_screen", "phone_screenshot",
             "browser_dom", "browser_evaluate", "browser_screenshot"}) {
      boolean browser = method.startsWith("browser_");
      if (browser && !getSharedPreferences("device", 0).getBoolean("browser_tools", false))
        continue;
      String rpcMethod = method.startsWith("phone_") || browser ? "tools/call" : method;
      if (method.startsWith("phone_") && PhoneAccessibilityService.active == null) {
        AppLogs.write(this, false, "WARN", "runtime", operation.optString("id"), "无障碍未开启，跳过屏幕与截图检查");
        state("running", "无障碍未开启，跳过屏幕与截图检查");
        continue;
      }
      JSONObject arguments = new JSONObject();
      if (method.equals("browser_evaluate"))
        arguments.put("script", "document.readyState");
      JSONObject params = rpcMethod.equals("tools/call")
          ? new JSONObject()
                .put("name", method.startsWith("phone_") || browser ? method : "device_status")
                .put("arguments", arguments)
          : new JSONObject();
      if (method.equals("initialize"))
        params.put("protocolVersion", "2025-03-26")
            .put("capabilities", new JSONObject())
            .put("clientInfo", new JSONObject().put("name", "aixs-settings").put("version", "1"));
      java.net.HttpURLConnection connection =
          (java.net.HttpURLConnection) new java.net.URL("http://127.0.0.1:" + DeviceMcpServer.PORT + "/mcp")
              .openConnection();
      connection.setRequestMethod("POST");
      connection.setConnectTimeout(3000);
      connection.setReadTimeout(10000);
      connection.setDoOutput(true);
      connection.setRequestProperty("Authorization", "Bearer " + DeviceMcpServer.token(this));
      connection.setRequestProperty("Content-Type", "application/json");
      try {
        byte[] body = new JSONObject()
                          .put("jsonrpc", "2.0")
                          .put("id", 1)
                          .put("method", rpcMethod)
                          .put("params", params)
                          .toString()
                          .getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = connection.getOutputStream()) {
          out.write(body);
        }
        if (connection.getResponseCode() != 200)
          throw new IOException("手机工具 HTTP " + connection.getResponseCode());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = connection.getInputStream()) {
          RuntimeManager.copy(in, bytes);
        }
        JSONObject reply = new JSONObject(bytes.toString("UTF-8"));
        if (reply.has("error"))
          throw new IOException(reply.getJSONObject("error").optString("message"));
        JSONObject result = reply.getJSONObject("result");
        if (result.optBoolean("isError"))
          throw new IOException(result.getJSONArray("content").getJSONObject(0).optString("text"));
        if (browser && !method.equals("browser_screenshot")) {
          JSONObject value = new JSONObject(result.getJSONArray("content").getJSONObject(0).getString("text"));
          if (!value.optBoolean("ok"))
            throw new IOException(value.optString("error", "页面检查失败"));
        }
        if (method.startsWith("phone_") || browser)
          log(method + " 实际调用通过");
        if (method.equals("tools/list"))
          log("内置 MCP：" + result.getJSONArray("tools").length() + " 个工具已连接");
        if (method.equals("tools/call"))
          log("设备状态：" + result.getJSONArray("content").getJSONObject(0).getString("text"));
      } finally {
        connection.disconnect();
      }
    }
    log("内置 MCP 初始化、工具发现与调用检查通过。");
  }
  private void startBackend() throws Exception {
    if (backend != null && backend.isAlive() && NativeEngine.ready()) {
      log("Agent 服务已在运行。");
      return;
    }
    manager.stopBackend();
    manager.deployNative();
    ProcessBuilder builder = manager.process(manager.guest(
        "/usr/bin/env", "AI_XS_STATE_DIR=/opt/aixs/native/.state", "python3", "/opt/aixs/native/native_host.py"));
    backend = builder.start();
    NativeEngine.attach(backend, value -> {
      boolean failed = value.startsWith("[ERROR]");
      AppLogs.write(this, true, failed ? "ERROR" : "DEBUG", "backend", "", value);
      if (failed) AppLogs.write(this, false, "ERROR", "backend", "", value.split("\n", 2)[0]);
    });
    try {
      NativeEngine.awaitReady();
      if (!backend.isAlive())
        throw new IOException("Agent 服务未能启动");
      log("Ai-XS 原生 Agent 服务已启动，使用私有进程通信。");
    } catch (Exception error) {
      backend.destroy();
      backend = null;
      throw new IOException("Agent 服务启动失败，请查看日志：" + NativeEngine.message(error));
    }
  }
  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
  @Override
  public void onDestroy() {
    resourceHandler.removeCallbacks(resourceSample);
    if (device != null)
      device.close();
    if (backend != null)
      backend.destroy();
    if (wake != null && wake.isHeld())
      wake.release();
    jobs.shutdown();
    super.onDestroy();
  }
}
