package app.aixs.mobile;

import java.util.Locale;
import java.util.regex.Pattern;

/** Turns engine and protocol errors into short Chinese messages while raw details stay in logs. */
final class ErrorMessage {
  private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fff]");

  private ErrorMessage() {}

  static String friendly(String raw) {
    String value = raw == null ? "" : raw.trim();
    if (value.isEmpty()) return "操作失败，请稍后重试。";
    if (CHINESE.matcher(value).find()) return value;
    String text = value.toLowerCase(Locale.ROOT);

    if (mentionsSubagent(text) && (text.contains("resume") || text.contains("unloaded")
        || text.contains("not loaded") || text.contains("parent first")))
      return "无法恢复子代理：该子代理尚未载入。请先恢复其父会话，或重新打开父会话后再试。";
    if (text.contains("too many active sessions"))
      return "连接数已满，请稍后重试。";
    if ((text.contains("session") || text.contains("thread")) && notFound(text))
      return "会话不存在或已失效，请返回会话列表重新打开。";
    if (text.contains("turn") && notFound(text))
      return "当前任务已结束或不存在，请重新发送。";
    if (text.contains("unauthorized") || text.contains("invalid api key")
        || text.contains("authentication") || text.contains("not logged in")
        || text.contains("login required") || text.contains("401"))
      return "API 鉴权失败，请检查设置中的密钥或登录状态。";
    if (text.contains("insufficient quota") || text.contains("quota") || text.contains("billing")
        || text.contains("credit") || text.contains("402") || text.contains("403"))
      return "API 额度不足或当前账号无权访问，请检查账户状态。";
    if (text.contains("rate limit") || text.contains("too many requests") || text.contains("429"))
      return "请求过于频繁，请稍后重试。";
    if (mentionsContext(text))
      return "会话上下文超出模型限制，请新建会话或缩短任务。";
    if (text.contains("timeout") || text.contains("timed out") || text.contains("deadline exceeded"))
      return "Agent 响应超时，请检查网络后重试。";
    if (mentionsNetwork(text))
      return "与 Agent 的连接中断，请检查网络后重试。";
    if (mentionsProcess(text))
      return "Agent 进程异常退出，请重试；若持续出现，请查看运行日志。";
    if (text.contains("model not found") || text.contains("unknown model")
        || text.contains("unsupported model") || text.contains("invalid model")
        || text.contains("model is not available"))
      return "模型不可用，请在设置中重新选择模型。";
    if (text.contains("no such file") || text.contains("file not found")
        || text.contains("cannot open file") || text.contains("enoent"))
      return "文件不存在或已被移动，请检查路径后重试。";
    if (text.contains("permission denied") || text.contains("not allowed")
        || text.contains("access denied") || text.contains("eperm") || text.contains("eacces"))
      return "操作被系统拒绝，请检查权限设置。";
    if (text.contains("canceled") || text.contains("cancelled") || text.contains("aborted")
        || text.contains("interrupted"))
      return "操作已取消。";
    if (text.contains("invalid") || text.contains("bad request") || text.contains("validation")
        || text.contains("missing required") || text.contains("400"))
      return "请求参数不正确，请重试或检查设置。";
    return "操作失败，请稍后重试。详细信息已写入运行日志。";
  }

  private static boolean mentionsSubagent(String text) {
    return text.contains("sub-agent") || text.contains("subagent") || text.contains("sub agent")
        || text.contains("multi-agent");
  }

  private static boolean notFound(String text) {
    return text.contains("not found") || text.contains("unknown") || text.contains("missing")
        || text.contains("no such") || text.contains("expired") || text.contains("does not exist");
  }

  private static boolean mentionsContext(String text) {
    return text.contains("context length") || text.contains("context window")
        || text.contains("too many tokens") || text.contains("maximum context")
        || text.contains("token limit") || text.contains("context limit");
  }

  private static boolean mentionsNetwork(String text) {
    return text.contains("econnrefused") || text.contains("enotfound") || text.contains("network")
        || text.contains("connection refused") || text.contains("connection reset")
        || text.contains("stream disconnected") || text.contains("connection closed")
        || text.contains("broken pipe") || text.contains("socket") || text.contains("dns")
        || text.contains("offline") || text.contains("eof");
  }

  private static boolean mentionsProcess(String text) {
    return text.contains("process exited") || text.contains("exited with code")
        || text.contains("terminated") || text.contains("killed") || text.contains("signal")
        || text.contains("failed to start") || text.contains("not running");
  }
}
