package app.aixs.mobile;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.StatFs;
import android.util.AtomicFile;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONObject;

/** One shared runtime; serial, versioned installations preserve user data. */
public final class RuntimeManager {
  private final Context context;
  public final File runtime, root;
  private final Consumer<String> logger;
  public RuntimeManager(Context context, Consumer<String> logger) {
    this.context = context.getApplicationContext();
    this.logger = logger;
    runtime = new File(context.getFilesDir(), "runtime");
    root = new File(runtime, "rootfs");
  }
  private void log(String text) {
    logger.accept(text);
  }
  public boolean initialized() {
    return new File(runtime, "ready").isFile();
  }
  public boolean complete() {
    return new File(runtime, "complete").isFile();
  }
  public String installed(String agent) {
    try {
      return json(new File(root, "opt/agents/" + agent + "/installed.json")).getString("version");
    } catch (Exception e) {
      return "未安装";
    }
  }
  public static JSONObject json(File file) throws Exception {
    return new JSONObject(new String(read(file), StandardCharsets.UTF_8));
  }
  public static byte[] read(File file) throws IOException {
    if (file.length() > 1024 * 1024)
      throw new IOException("状态文件过大");
    try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      copy(in, out);
      return out.toByteArray();
    }
  }
  public static void copy(InputStream in, OutputStream out) throws IOException {
    byte[] buf = new byte[65536];
    int n;
    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
  }
  public static void write(File file, String text) throws IOException {
    file.getParentFile().mkdirs();
    AtomicFile atomic = new AtomicFile(file);
    FileOutputStream out = atomic.startWrite();
    try {
      out.write(text.getBytes(StandardCharsets.UTF_8));
      atomic.finishWrite(out);
    } catch (IOException e) {
      atomic.failWrite(out);
      throw e;
    }
  }
  private JSONObject assetJson(String name) throws Exception {
    try (InputStream in = context.getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      copy(in, out);
      return new JSONObject(out.toString("UTF-8"));
    }
  }
  private static String hex(byte[] bytes) {
    StringBuilder value = new StringBuilder();
    for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255));
    return value.toString();
  }
  public ProcessBuilder process(List<String> command) {
    ProcessBuilder p = new ProcessBuilder(command);
    Map<String, String> env = p.environment();
    env.clear();
    String lib = context.getApplicationInfo().nativeLibraryDir;
    env.put("PATH", "/system/bin");
    env.put("HOME", context.getFilesDir().getAbsolutePath());
    env.put("TMPDIR", new File(runtime, "tmp").getAbsolutePath());
    env.put("PROOT_TMP_DIR", new File(runtime, "tmp").getAbsolutePath());
    env.put("LD_LIBRARY_PATH", lib);
    env.put("PROOT_LOADER", lib + "/libloader.so");
    env.put("PROOT_LOADER_32", lib + "/libloader32.so");
    return p;
  }
  public List<String> guest(String... args) {
    String lib = context.getApplicationInfo().nativeLibraryDir;
    List<String> command = new ArrayList<>(Arrays.asList(lib + "/libproot.so", "--kill-on-exit", "--link2symlink", "-0",
        "-r", root.getAbsolutePath(), "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/", "/usr/bin/env", "-i",
        "HOME=/root", "PATH=/opt/node/bin:/usr/local/bin:/usr/bin:/bin", "LANG=C.UTF-8", "TMPDIR=/tmp",
        "NODE_OPTIONS=--max-old-space-size=1024", "UV_THREADPOOL_SIZE=2", "AI_XS_SINGLE_AGENT=1",
        "CODEX_ZH_PROJECTS=/root/projects", "AI_XS_CLAUDE_COMMAND=[\"/usr/local/bin/claude\"]",
        "AI_XS_DEEPSEEK_COMMAND=[\"/usr/local/bin/dsh\"]"));
    try {
      command.add("AI_XS_DEVICE_TOKEN=" + DeviceMcpServer.token(context));
    } catch (Exception e) {
      throw new IllegalStateException("无法创建手机工具凭据", e);
    }
    command.add("AI_XS_ENGINE_HOME=/root");
    Collections.addAll(command, args);
    return command;
  }
  public String run(List<String> command, boolean guest, int timeout) throws Exception {
    ProcessBuilder builder = guest ? process(command) : new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    Process child = builder.start();
    ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    ScheduledFuture<?> expiry = timer.schedule(() -> child.destroyForcibly(), timeout, TimeUnit.SECONDS);
    StringBuilder tail = new StringBuilder();
    try (BufferedReader reader =
             new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.length() > 2000)
          line = line.substring(0, 2000);
        log(line);
        tail.append(line).append('\n');
        if (tail.length() > 4000)
          tail.delete(0, tail.length() - 4000);
      }
    } finally {
      expiry.cancel(false);
      timer.shutdownNow();
    }
    if (child.waitFor() != 0)
      throw new IOException("命令未成功完成，请查看最后的安装日志");
    return tail.toString();
  }
  public void initialize() throws Exception {
    if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"))
      throw new IOException("当前安装包需要 ARM64 手机");
    if (new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes() < 1536L * 1024 * 1024)
      throw new IOException("请至少预留 1.5 GB 存储空间");
    runtime.mkdirs();
    new File(runtime, "tmp").mkdirs();
    if (!initialized() && root.exists()) {
      if (!new File(root, "usr/bin/env").isFile() || !new File(root, "etc/os-release").isFile())
        throw new IOException("基础系统不完整，已保留现有文件；请导出日志后检查，避免覆盖数据");
      log("发现上次中断留下的基础系统，先验证再继续补装…");
      run(guest("/bin/sh", "-ec", "test -s /etc/os-release; echo RUNTIME_RECOVERY_OK"), true, 30);
      write(new File(runtime, "ready"), "1\n");
    }
    if (!initialized()) {
      JSONObject manifest = assetJson("runtime/manifest.json");
      File archive = new File(context.getCacheDir(), "rootfs.tar.gz");
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      log("校验内置 ARM64 基础系统，仅创建一份共享环境…");
      try (InputStream in = context.getAssets().open("runtime/ubuntu-base.bin");
          OutputStream out = new FileOutputStream(archive)) {
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) != -1) {
          digest.update(buffer, 0, n);
          out.write(buffer, 0, n);
        }
      }
      if (!hex(digest.digest()).equals(manifest.getString("archiveSha256")))
        throw new IOException("基础系统校验失败");
      File staging = new File(runtime, "rootfs-install-" + UUID.randomUUID());
      staging.mkdirs();
      try {
        run(Arrays.asList(
                "/system/bin/toybox", "tar", "-xof", archive.getAbsolutePath(), "-C", staging.getAbsolutePath()),
            false, 120);
      } catch (Exception error) {
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(staging.toPath())) {
          files.sorted(Comparator.reverseOrder()).forEach(path -> {
            try {
              java.nio.file.Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
          });
        }
        throw error;
      }
      if (root.exists())
        throw new IOException("发现未完成的旧环境；已保留，请导出日志后修复，避免覆盖数据");
      if (!staging.renameTo(root))
        throw new IOException("无法启用共享环境");
      archive.delete();
      new File(root, "tmp").mkdirs();
      write(new File(root, "etc/resolv.conf"), "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
      write(new File(runtime, "ready"), "1\n");
    } else
      log("复用已有环境，保留 Agent、配置、历史和项目。");
    log("检查和补装共享 Python、Git、TLS 与下载工具…");
    run(guest("/bin/sh", "-ec",
            "export DEBIAN_FRONTEND=noninteractive; if ! (command -v python3 && command -v git && command -v curl && "
            + "command -v xz && test -s /etc/ssl/certs/ca-certificates.crt) >/dev/null 2>&1; then apt-get -o "
            + "APT::Sandbox::User=root -o Acquire::Languages=none -o Acquire::Retries=2 -o Acquire::http::Timeout=30 "
            + "update; apt-get -o APT::Sandbox::User=root -o Acquire::Retries=2 install -y --no-install-recommends "
            + "python3 git curl xz-utils ca-certificates; fi; python3 --version; git --version"),
        true, 1200);
    JSONObject node = assetJson("runtime/catalog.json").getJSONObject("node");
    String version = node.getString("version"), sha = node.getString("sha256");
    if (!version.matches("v[0-9.]+") || !sha.matches("[0-9a-f]{64}"))
      throw new IOException("Node 安装目录信息无效");
    log("检查共享 Node.js " + version + "…");
    String nodeName = "node-" + version + "-linux-arm64";
    run(guest("/bin/sh", "-ec",
            "if test -x /opt/node/bin/node && test \"$(/opt/node/bin/node --version)\" = " + version
                + ("; then /opt/node/bin/node --version; exit 0; fi; mkdir -p /opt; curl -fL --connect-timeout 20 "
                   + "--max-time 600 --retry 2 -o /tmp/node.tar.xz https://nodejs.org/dist/")
                + version + "/" + nodeName + ".tar.xz; echo '" + sha
                + "  /tmp/node.tar.xz' | sha256sum -c -; tar -xJf /tmp/node.tar.xz -C /opt; ln -sfn " + nodeName
                + (" /opt/node.new; mv -Tf /opt/node.new /opt/node; rm -f /tmp/node.tar.xz; /opt/node/bin/node "
                   + "--version; /opt/node/bin/npm --version")),
        true, 1200);
    deployTools();
    run(guest("/bin/sh", "-ec", "apt-get clean; rm -rf /var/lib/apt/lists/*"), true, 60);
    check();
    write(new File(runtime, "complete"), "1\n");
    log("初始化完成。三个 Agent 共用这一份环境。");
  }
  public void check() throws Exception {
    if (!initialized())
      throw new IOException("请先在设置 → 运行环境中初始化");
    run(guest("/bin/sh", "-ec",
            "echo SHARED_RUNTIME_OK; uname -m; python3 --version; git --version; /opt/node/bin/node --version; "
            + "/opt/node/bin/npm --version; du -sh /usr /opt /root /var 2>/dev/null"),
        true, 60);
  }
  public void deployTools() throws Exception {
    File tools = new File(root, "opt/aixs/tools");
    tools.mkdirs();
    new File(tools, "workbench_check.py").delete();
    for (String name : new String[] {"agent_package.py", "backend_control.py"}) {
      try (InputStream in = context.getAssets().open("runtime/" + name);
          OutputStream out = new FileOutputStream(new File(tools, name))) {
        copy(in, out);
      }
    }
  }
  private void requireReady() throws IOException {
    if (!new File(runtime, "complete").isFile())
      throw new IOException("请先在设置 → 运行环境完成初始化");
  }
  public void installAgent(String agent) throws Exception {
    JSONObject item = assetJson("runtime/catalog.json").getJSONObject("agents").getJSONObject(agent);
    installAgent(agent, item.getString("version"));
  }
  public void installAgent(String agent, String version) throws Exception {
    ReleaseVersions.npmPackage(agent);
    ReleaseVersions.compare(version, version);
    requireReady();
    if (new StatFs(root.getAbsolutePath()).getAvailableBytes() < 1024L * 1024 * 1024)
      throw new IOException("安装 Agent 前请至少预留 1 GB 空间");
    deployTools();
    log("从官方发布包安装 " + agent + " " + version + "…");
    run(guest("python3", "/opt/aixs/tools/agent_package.py", "npm", agent, version), true, 1500);
    log("Agent 已安装，已有历史和 API 配置保留。");
  }
  public void updateAgent(String agent, String version) throws Exception {
    String current = installed(agent);
    if (!current.equals("未安装") && ReleaseVersions.compare(version, current) <= 0) {
      log(agent + " 当前版本 " + current + " 已是最新或更高版本，无需更新。");
      return;
    }
    installAgent(agent, version);
  }
  public JSONObject agentVersions(String agent) throws Exception {
    ReleaseVersions.npmPackage(agent);
    requireReady();
    deployTools();
    Process child = process(guest("python3", "/opt/aixs/tools/agent_package.py", "list", agent)).redirectErrorStream(true).start();
    ScheduledExecutorService timeout = Executors.newSingleThreadScheduledExecutor();
    ScheduledFuture<?> expiry = timeout.schedule(() -> child.destroyForcibly(), 30, TimeUnit.SECONDS);
    try (InputStream in = child.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192]; int count;
      while ((count = in.read(buffer)) != -1) {
        if (out.size() + count > 1024 * 1024) { child.destroyForcibly(); throw new IOException("版本列表过大"); }
        out.write(buffer, 0, count);
      }
      if (child.waitFor() != 0) throw new IOException("读取版本列表失败，请检查环境");
      return new JSONObject(out.toString("UTF-8"));
    } finally { expiry.cancel(false); timeout.shutdownNow(); }
  }
  public void manageAgentVersion(String action, String agent, String release) throws Exception {
    ReleaseVersions.npmPackage(agent);
    if (!(action.equals("select") || action.equals("remove")) || release == null || !release.matches("[A-Za-z0-9][A-Za-z0-9.+_-]{0,100}"))
      throw new IOException("版本操作参数无效");
    requireReady(); deployTools();
    run(guest("python3", "/opt/aixs/tools/agent_package.py", action, agent, release), true, 180);
  }
  public void importAgent(String agent, String uri, String url, String expectedHash) throws Exception {
    requireReady();
    deployTools();
    File target = new File(root, "tmp/import-" + UUID.randomUUID() + ".zip");
    try {
      if (url != null) {
        if (expectedHash == null || !expectedHash.matches("[0-9a-fA-F]{64}"))
          throw new IOException("请填写发布者提供的 SHA-256");
        download(url, target, expectedHash.toLowerCase(Locale.ROOT));
      } else {
        try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(uri));
            OutputStream out = new FileOutputStream(target)) {
          if (in == null)
            throw new IOException("无法读取所选文件");
          limitedCopy(in, out, null);
        }
      }
      run(guest("python3", "/opt/aixs/tools/agent_package.py", "bundle", agent, "/tmp/" + target.getName()), true,
          1500);
      log("安装包校验、版本检查和部署已完成。");
    } finally {
      target.delete();
    }
  }
  private void limitedCopy(InputStream in, OutputStream out, MessageDigest digest) throws IOException {
    byte[] buffer = new byte[65536];
    int n;
    long size = 0, next = 10 * 1024 * 1024;
    while ((n = in.read(buffer)) != -1) {
      size += n;
      if (size > 2L * 1024 * 1024 * 1024)
        throw new IOException("安装包超过 2 GB 限制");
      if (digest != null)
        digest.update(buffer, 0, n);
      out.write(buffer, 0, n);
      if (size >= next) {
        log("已读取 " + size / 1024 / 1024 + " MiB");
        next += 10 * 1024 * 1024;
      }
    }
  }
  private void download(String value, File target, String sha) throws Exception {
    URL url = new URL(value);
    for (int redirects = 0; redirects < 6; redirects++) {
      String host = url.getHost().toLowerCase(Locale.ROOT);
      if (!url.getProtocol().equals("https") || url.getUserInfo() != null
          || (url.getPort() != -1 && url.getPort() != 443)
          || !(host.equals("github.com") || host.endsWith(".githubusercontent.com")))
        throw new IOException("GitHub 安装仅接受 GitHub Releases 的 HTTPS 链接");
      HttpURLConnection c = (HttpURLConnection) url.openConnection();
      c.setInstanceFollowRedirects(false);
      c.setConnectTimeout(20000);
      c.setReadTimeout(30000);
      try {
        int status = c.getResponseCode();
        if (status >= 300 && status < 400) {
          url = new URL(url, c.getHeaderField("Location"));
          continue;
        }
        if (status != 200)
          throw new IOException("下载失败，HTTP " + status);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(target)) {
          limitedCopy(in, out, digest);
        }
        if (!sha.equals(hex(digest.digest())))
          throw new IOException("GitHub 安装包 SHA-256 不匹配");
        return;
      } finally {
        c.disconnect();
      }
    }
    throw new IOException("下载重定向次数过多");
  }
  public void checkAgentInterfaces() throws Exception {
    JSONObject result = NativeEngine.request("native/check", new JSONObject()).get(180, TimeUnit.SECONDS);
    org.json.JSONArray entries = result.getJSONArray("data");
    for (int i = 0; i < entries.length(); i++) {
      JSONObject item = entries.getJSONObject(i);
      log(item.getString("agent") + " · " + item.getString("version") + " · 模型 " + item.getInt("models")
          + " · MCP 工具 " + item.getInt("tools"));
    }
    log(result.getString("detail"));
  }
  public void stopBackend() throws Exception {
    if (initialized()) {
      deployTools();
      run(guest("python3", "/opt/aixs/tools/backend_control.py"), true, 30);
    }
  }
  private File prepareNativeState(File service) throws IOException {
    File state = new File(service, ".state"), legacy = new File(root, "opt/aixs/web/.state");
    if (legacy.isDirectory() && !state.exists()) {
      if (!legacy.renameTo(state))
        throw new IOException("无法迁移现有配置和历史，原数据已保留");
      log("已有配置和历史已迁移到原生服务目录。");
    }
    if (!state.isDirectory() && !state.mkdirs())
      throw new IOException("无法创建 Agent 状态目录");
    new File(service, "deepseek_provider_launcher.mjs").delete();
    File oldWeb = new File(root, "opt/aixs/web");
    String[] obsolete = {"assistant_tools.py", "claude_bridge.py", "deepseek_bridge.py", "engine_manager.py",
        "local_tools.py", "manage.py", "mcp_manager.py", "provider_manager.py", "rpc_process.py", "server.py",
        "deepseek_provider_launcher.mjs", "index.html", "styles.css", "app.js"};
    for (String name : obsolete) new File(oldWeb, name).delete();
    File cache = new File(oldWeb, "__pycache__");
    File[] bytecode = cache.listFiles();
    if (bytecode != null)
      for (File file : bytecode)
        for (String name : obsolete)
          if (name.endsWith(".py") && file.getName().startsWith(name.substring(0, name.length() - 3) + ".")
              && file.getName().endsWith(".pyc"))
            file.delete();
    cache.delete();
    oldWeb.delete();
    new File(state, "token").delete();
    return state;
  }
  public void deployNative() throws Exception {
    requireReady();
    File service = new File(root, "opt/aixs/native");
    service.mkdirs();
    new File(root, "root/projects").mkdirs();
    File state = prepareNativeState(service);
    File mcp = new File(state, "mcp-servers.json");
    JSONObject servers = mcp.isFile() ? json(mcp) : new JSONObject();
    servers.put("aixs-device",
        new JSONObject()
            .put("url", "http://127.0.0.1:" + DeviceMcpServer.PORT + "/mcp")
            .put("bearer_token_env_var", "AI_XS_DEVICE_TOKEN"));
    write(mcp, servers.toString());
    try (ZipInputStream zip = new ZipInputStream(context.getAssets().open("runtime/native.zip"))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        if (name.contains("/") || name.startsWith(".") || !name.matches("[A-Za-z0-9_.-]+"))
          throw new IOException("Agent 服务资源路径无效");
        try (OutputStream out = new FileOutputStream(new File(service, name))) {
          copy(zip, out);
        }
      }
    }
  }
}
