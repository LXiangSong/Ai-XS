package app.aixs.mobile;

import org.json.JSONArray;
import org.json.JSONObject;

/** Chinese labels for the v2 collaboration contract; no UI or network side effects. */
final class CollaborationStatus {
  static String execution(String status) {
    switch (status == null ? "" : status) {
      case "draft": return "待开始";
      case "blocked": return "等待依赖";
      case "queued": return "排队";
      case "starting": return "启动中";
      case "running": return "执行中";
      case "waiting_input": return "等待用户";
      case "stopping": return "正在停止";
      case "succeeded": return "执行完成";
      case "failed": return "失败";
      case "interrupted": return "已中断";
      case "paused": return "暂停派发";
      case "cancelled": return "已取消";
      case "awaiting_acceptance": return "待验收";
      case "completed": return "已确认";
      case "partial_failure": return "部分失败";
      case "needs_reconciliation": return "待核对";
      default: return status == null ? "" : status;
    }
  }

  static String verification(String status) {
    switch (status == null ? "" : status) {
      case "not_run": return "未验证";
      case "passed": return "验证通过";
      case "failed": return "验证失败";
      case "manual_required": return "待人工核验";
      default: return status == null || status.isEmpty() ? "未验证" : status;
    }
  }

  static String delivery(String status) {
    switch (status == null ? "" : status) {
      case "executing": return "执行中";
      case "verification_failed": return "验证失败";
      case "awaiting_acceptance": return "待验收";
      case "accepted": return "已验收";
      default: return status == null || status.isEmpty() ? "未知" : status;
    }
  }

  static String kind(String kind) {
    switch (kind == null ? "" : kind) {
      case "work": return "实现";
      case "research": return "研究";
      case "review": return "审核";
      case "summary": return "汇总";
      default: return kind == null || kind.isEmpty() ? "任务" : kind;
    }
  }

  static String budgetLine(JSONObject usage) {
    if (usage == null) return "";
    JSONObject limits = usage.optJSONObject("limits");
    StringBuilder text = new StringBuilder();
    text.append("执行 ").append(usage.optInt("attempts")).append(" 次");
    if (limits != null) text.append(" / 上限 ").append(limits.optInt("maxAttempts"));
    text.append(" · 用时 ").append(duration(usage.optLong("elapsedSeconds")));
    int tokens = usage.optInt("reportedTokens");
    text.append(" · 已上报 ").append(tokens == 0 ? "0" : String.valueOf(tokens)).append(" 词元");
    if (usage.optBoolean("tokensPartial")) text.append("（可能不完整）");
    if (limits != null && limits.optInt("maxReportedTokens") > 0)
      text.append(" / 上限 ").append(limits.optInt("maxReportedTokens"));
    return text.toString();
  }

  static String pauseReason(String reason) {
    switch (reason == null ? "" : reason) {
      case "user": return "用户暂停";
      case "budget:runtime": return "运行时间到限";
      case "budget:reported_tokens": return "已上报词元到限";
      case "budget:attempts": return "执行次数用尽";
      case "recovery": return "等待中断恢复核对";
      case "agent_stopped": return "Agent 已停止";
      default: return reason == null ? "" : reason;
    }
  }

  static String verificationSummary(JSONObject verification) {
    if (verification == null) return "";
    JSONArray checks = verification.optJSONArray("checks");
    int passed = 0, failed = 0;
    for (int i = 0; checks != null && i < checks.length(); i++) {
      JSONObject check = checks.optJSONObject(i);
      if (check == null) continue;
      if (check.optBoolean("passed")) passed++; else failed++;
    }
    StringBuilder text = new StringBuilder();
    if (checks != null && checks.length() > 0) {
      text.append("验证 ").append(passed).append(" 通过");
      if (failed > 0) text.append(" · ").append(failed).append(" 失败");
    }
    JSONObject review = verification.optJSONObject("review");
    if (review != null) {
      if (text.length() > 0) text.append(" · ");
      text.append(review.optBoolean("approved") ? "审核通过" : "审核未通过");
    }
    if (verification.optBoolean("cancelled")) {
      if (text.length() > 0) text.append(" · ");
      text.append("已中断");
    }
    return text.length() == 0 ? "无自动验证" : text.toString();
  }

  static String duration(long seconds) {
    if (seconds <= 0) return "0 秒";
    long hours = seconds / 3600, minutes = seconds % 3600 / 60;
    if (hours > 0) return hours + " 小时 " + minutes + " 分";
    if (minutes > 0) return minutes + " 分 " + (seconds % 60) + " 秒";
    return seconds + " 秒";
  }
}
