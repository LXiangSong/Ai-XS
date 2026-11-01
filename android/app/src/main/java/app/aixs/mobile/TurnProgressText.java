package app.aixs.mobile;

import org.json.JSONObject;

/** Only labels actual Agent events; elapsed time never implies completion percentage. */
final class TurnProgressText {
  static String title(JSONObject progress) {
    if (progress == null) return "等待事件";
    String phase = progress.optString("phase"), detail = progress.optString("detail");
    if ("starting".equals(phase)) return "启动中";
    if ("waiting".equals(phase)) return "等待接口";
    if ("receiving".equals(phase)) return "接收响应";
    if ("planning".equals(phase)) return "规划任务";
    if ("input".equals(phase)) return "等待回答";
    if ("thinking".equals(phase)) return "思考中";
    if ("responding".equals(phase)) return "输出中";
    if ("compacting".equals(phase)) return "压缩上下文";
    if ("approval".equals(phase)) return "等待确认";
    if ("preparingTool".equals(phase)) return "准备工具";
    if ("tool".equals(phase)) return detail.isEmpty() ? "执行工具" : detail;
    if ("retrying".equals(phase)) {
      int attempt = progress.optInt("attempt"), max = progress.optInt("maxRetries");
      return "接口重试" + (attempt > 0 ? " " + attempt + (max > 0 ? "/" + max : "") : "");
    }
    return "处理中";
  }

  static String retryDetail(JSONObject progress) {
    String detail = "";
    int status = progress.optInt("httpStatus"), delay = progress.optInt("retryDelayMs");
    if (status > 0) detail = "HTTP " + status;
    if (delay > 0) detail += (detail.isEmpty() ? "" : " · ") + "重试间隔 " + (long)Math.ceil(delay / 1000d) + " 秒";
    return detail;
  }
}
