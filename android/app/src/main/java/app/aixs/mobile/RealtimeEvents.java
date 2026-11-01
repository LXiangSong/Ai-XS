package app.aixs.mobile;

import org.json.*;
import java.util.Iterator;

/** Live protocol reducer: shared by every engine in the native conversation UI. */
final class RealtimeEvents {
  static String renderSignature(JSONObject turn,boolean running,boolean settled,String cached) {
    // Inline collaboration turns remain "completed" so normal turn controls do not own their execution.
    // Their members can still stream, finish or fail, including after the last active item settles.
    if (turn.has("workflowId")) {
      // Budget clocks and transport revisions change on polling without changing
      // the transcript. Rebuilding its Markdown views for those ticks flashes.
      JSONObject visible = new JSONObject();
      try {
        for (String key : new String[]{"id", "workflowId", "status", "items", "collaborationStatus",
            "collaborationActive", "collaborationCompleted", "collaborationTotal", "historyHasMore",
            "historyBefore", "historyPartial", "historyOmittedItems"})
          if (turn.has(key)) visible.put(key, turn.opt(key));
        JSONObject delivery = turn.optJSONObject("delivery");
        visible.put("deliveryStatus", delivery == null ? "" : delivery.optString("status"));
        return visible.toString();
      } catch (JSONException ignored) { return turn.toString(); }
    }
    return !running&&settled&&cached!=null?cached:turn.toString();
  }
  static JSONObject item(JSONObject turn, String id, String kind) throws JSONException {
    JSONArray items = turn.optJSONArray("items");
    if (items == null) { items = new JSONArray(); turn.put("items", items); }
    for (int i = 0; i < items.length(); i++) {
      JSONObject value = items.optJSONObject(i);
      if (value != null && id.equals(value.optString("id"))) return value;
    }
    JSONObject value = new JSONObject().put("id", id).put("type", kind).put("status", "inProgress");
    items.put(value);
    return value;
  }

  static boolean apply(JSONObject turn, String method, JSONObject p) throws JSONException {
    if (turn == null) return false;
    String id = p.optString("itemId", "");
    if (method.equals("item/started") || method.equals("item/completed")) {
      JSONObject raw = p.optJSONObject("item");
      if (raw == null || raw.optString("id").isEmpty()) return false;
      JSONObject value = item(turn, raw.optString("id"), raw.optString("type", "unknown"));
      for (Iterator<String> keys = raw.keys(); keys.hasNext();) {
        String key = keys.next();
        if (raw.isNull(key) && !value.isNull(key) && key.matches("aggregatedOutput|text|content|summary")) continue;
        value.put(key, raw.get(key));
      }
      if (method.equals("item/completed") && active(value.optString("status"))) value.put("status", "completed");
      if (method.equals("item/completed") && (value.has("success") && !value.isNull("success") && !value.optBoolean("success") ||
          value.optString("type").equals("commandExecution") && !value.isNull("exitCode") && value.optInt("exitCode") != 0)) value.put("status","failed");
    } else if (!id.isEmpty() && (method.equals("item/agentMessage/delta") || method.equals("item/plan/delta"))) {
      JSONObject value = item(turn, id, method.contains("/plan/") ? "plan" : "agentMessage");
      value.put("text", ConversationContent.string(value,"text","") + ConversationContent.string(p,"delta",""));
    } else if (!id.isEmpty() && method.startsWith("item/reasoning/")) {
      ConversationContent.appendReasoning(item(turn, id, "reasoning"), p, method.contains("summary"));
    } else if (!id.isEmpty() && (method.equals("item/commandExecution/outputDelta") || method.equals("item/fileChange/outputDelta"))) {
      JSONObject value = item(turn, id, method.contains("commandExecution") ? "commandExecution" : "fileChange");
      value.put("aggregatedOutput", ConversationContent.string(value,"aggregatedOutput","") + ConversationContent.string(p,"delta",""));
    } else if (!id.isEmpty() && method.equals("item/fileChange/patchUpdated")) {
      item(turn, id, "fileChange").put("changes", p.opt("changes"));
    } else if (!id.isEmpty() && method.equals("item/mcpToolCall/progress")) {
      item(turn, id, "mcpToolCall").put("progressMessage", p.optString("message"));
    } else if (!id.isEmpty() && method.equals("item/commandExecution/terminalInteraction")) {
      item(turn, id, "commandExecution").put("progressMessage", "终端输入已发送");
    } else if (method.equals("item/autoApprovalReview/started") || method.equals("item/autoApprovalReview/completed")) {
      JSONObject review = p.optJSONObject("review");
      if (p.optString("reviewId").isEmpty()) return false;
      item(turn, "review:" + p.optString("reviewId"), "runtimeStatus").put("tool", "自动审批检查")
        .put("progressMessage", ConversationContent.string(review, "rationale", ""))
        .put("status", method.endsWith("/started") ? "inProgress" : "completed");
    } else if (method.equals("native/childTurn")) {
      JSONObject child = p.optJSONObject("childTurn");
      if (child == null) return false;
      JSONObject saved = turn.optJSONObject("nativeChildren");
      if (saved == null) {
        saved = new JSONObject();
        turn.put("nativeChildren", saved);
      }
      String key = p.optString("sourceThreadId", "") + ":" + p.optString("sourceTurnId", "");
      JSONObject entry = new JSONObject()
        .put("sourceThreadId", p.opt("sourceThreadId"))
        .put("sourceTurnId", p.opt("sourceTurnId"))
        .put("engine", p.optString("sourceEngine", ""))
        .put("model", p.optString("sourceModel", ""))
        .put("turn", child)
        .put("stats", p.opt("stats"));
      saved.put(key, entry);
    } else if (method.equals("turn/plan/updated")) {
      JSONObject value = item(turn, "aixs-turn-plan", "plan");
      StringBuilder text = new StringBuilder(ConversationContent.string(p, "explanation", ""));
      JSONArray steps = p.optJSONArray("plan");
      boolean done = steps != null && steps.length() > 0;
      for (int i = 0; steps != null && i < steps.length(); i++) {
        JSONObject step = steps.optJSONObject(i); if (step == null) continue;
        String status = step.optString("status"); done &= status.equals("completed");
        if (text.length() > 0) text.append('\n');
        text.append(status.equals("completed") ? "已完成" : status.equals("inProgress") ? "进行中" : "待执行")
          .append(" · ").append(step.optString("step"));
      }
      value.put("text", text.toString()).put("status", done ? "completed" : "inProgress");
    } else if (method.equals("turn/diff/updated")) {
      item(turn, "aixs-turn-diff", "turnDiff").put("tool", "累计修改").put("changes", p.optString("diff"));
    } else if (method.equals("hook/started") || method.equals("hook/completed")) {
      JSONObject run = p.optJSONObject("run");
      if (run == null || run.optString("id").isEmpty()) return false;
      item(turn, "hook:" + run.optString("id"), "hook").put("tool", run.optString("eventName", "Hook"))
        .put("progressMessage", ConversationContent.string(run, "statusMessage", ""))
        .put("result", run.opt("entries")).put("status", method.endsWith("/started") ? "inProgress" :
          run.optString("status").matches("failed|blocked") ? "failed" : "completed");
    } else if (method.equals("error")) {
      boolean retry = p.optBoolean("willRetry");
      item(turn, "aixs-runtime-error", "runtimeStatus").put("tool", retry ? "正在重试" : "执行错误")
        .put("result", p.opt("error")).put("status", retry ? "inProgress" : "failed");
    } else if (method.equals("model/rerouted")) {
      item(turn, "aixs-model-reroute", "runtimeStatus").put("tool", "模型已切换").put("status", "completed")
        .put("text", p.optString("fromModel") + " → " + p.optString("toModel"));
    } else if (method.matches("warning|guardianWarning|deprecationNotice|configWarning|modelProvider/authRecoveryStarted|modelProvider/authRecoveryCompleted|model/verification|model/safetyBuffering/updated|autoApprovalReview/strictReviewRequired|mcpServer/startupStatus/updated")) {
      String label=method.equals("warning")?"运行提示":method.equals("guardianWarning")?"审批提示":method.equals("deprecationNotice")?"兼容性提示":method.equals("configWarning")?"配置提示":
        method.endsWith("authRecoveryStarted")?"正在恢复 API 认证":method.endsWith("authRecoveryCompleted")?"API 认证已恢复":method.equals("model/verification")?"模型验证":
        method.equals("model/safetyBuffering/updated")?"内容审核":method.endsWith("strictReviewRequired")?"等待严格审批":"MCP 服务";
      String status=method.endsWith("Started")||method.endsWith("strictReviewRequired")?"inProgress":"completed";
      Object detail=p.opt("message");if(detail==null||detail==JSONObject.NULL)detail=p.opt("summary");if(detail==null||detail==JSONObject.NULL)detail=p.opt("details");
      if(method.equals("model/verification"))detail=p.opt("verifications");
      if(method.equals("model/safetyBuffering/updated")){status=p.optBoolean("showBufferingUi")?"inProgress":"completed";detail=p.opt("reasons");}
      if(method.equals("mcpServer/startupStatus/updated")){
        String state=p.optString("status");status=state.equals("starting")?"inProgress":state.equals("failed")?"failed":state.equals("cancelled")?"interrupted":"completed";
        detail=p.optString("name")+" · "+ConversationContent.string(p,"error",state);
      }
      item(turn,"runtime:"+method+(p.has("name")?":"+p.optString("name"):""),"runtimeStatus").put("tool",label).put("status",status).put("text",detail==null||detail==JSONObject.NULL?"":detail.toString());
    } else return false;
    if(p.optBoolean("nativeChild")){
      JSONObject raw=p.optJSONObject("item");String childId=!id.isEmpty()?id:raw==null?"":raw.optString("id");
      JSONArray values=turn.optJSONArray("items");
      for(int i=0;values!=null&&i<values.length();i++){
        JSONObject value=values.optJSONObject(i);if(value==null||!childId.equals(value.optString("id")))continue;
        for(String key:new String[]{"nativeChild","sourceEngine","sourceThreadId","sourceTurnId","sourceModel","parentToolUseId"})if(p.has(key))value.put(key,p.opt(key));
        if(value.optString("type").equals("agentMessage"))value.put("type","subagentMessage");
      }
    }
    return true;
  }

  static boolean active(String status) { return status.matches("inProgress|in_progress|pending|running|starting"); }

  static String label(JSONObject item, boolean running) {
    String kind = item.optString("type"), tool = ConversationContent.string(item, "tool", "");
    String parentTool = item.optString("parentToolUseId");
    boolean child = !parentTool.isEmpty() || item.optBoolean("nativeChild")
      || !item.optString("sourceThreadId").isEmpty();
    if (kind.equals("subAgentActivity")) {
      String phase = item.optString("kind");
      return phase.equals("started") ? "子代理 · 已启动" : phase.equals("interacted") ? "子代理 · 交互"
        : phase.equals("interrupted") ? "子代理 · 已中断" : phase.equals("completed") ? "子代理 · 已完成" : "子代理 · 状态";
    }
    if (kind.equals("collabAgentToolCall")) {
      String action = tool.replace("_", "").toLowerCase(java.util.Locale.ROOT);
      String label = action.startsWith("spawn") ? "启动子代理" : action.startsWith("wait") ? "等待子代理"
        : action.startsWith("send") ? "通知子代理" : action.startsWith("close") ? "关闭子代理"
        : action.startsWith("resume") ? "恢复子代理" : "子代理调用";
      String model = ConversationContent.string(item, "model", "");
      return model.isEmpty() ? label : label + " · " + NativeHomeActivity.shortModel(model);
    }
    String label = child && kind.equals("subagentMessage") ? "子任务回复"
      : child && kind.equals("reasoning") ? "子任务思考"
      : child && kind.equals("commandExecution") ? "子任务命令"
      : child && kind.equals("fileChange") ? "子任务修改文件"
      : child && kind.equals("taskProgress") ? "子任务进度"
      : child && kind.equals("webSearch") ? "子任务搜索"
      : child && kind.equals("plan") ? "子任务计划"
      : child && kind.equals("hook") ? "子任务 Hook" + (tool.isEmpty() ? "" : " · " + tool)
      : child && kind.equals("runtimeStatus") ? "子任务状态" + (tool.isEmpty() ? "" : " · " + tool)
      : child ? (tool.isEmpty() || tool.equals("子任务") || tool.equals("后台任务") || tool.equals("Codex 子任务") ? "子任务" : "子任务 · " + tool)
      : kind.equals("imageView") ? "读取图片"
      : kind.equals("imageGeneration") ? "生成图片"
      : kind.equals("functionCallOutput") ? "工具结果 · " + item.optString("name")
      : kind.equals("hookPrompt") ? "Hook 提示"
      : kind.equals("sleep") ? "等待 · " + item.optLong("durationMs") + " ms"
      : kind.equals("enteredReviewMode") ? "开始审查"
      : kind.equals("exitedReviewMode") ? "审查结果"
      : kind.equals("contextCompaction") ? "整理上下文"
      : kind.equals("fileReport") ? fileReportLabel(item)
      : kind.equals("subagentMessage") ? "子任务输出"
      : kind.equals("reasoning") ? "思考过程"
      : kind.equals("plan") ? "任务计划"
      : kind.equals("commandExecution") ? "运行命令"
      : (kind.equals("fileChange") || kind.equals("turnDiff")) ? "文件修改"
      : kind.equals("webSearch") ? "网页搜索"
      : kind.equals("hook") ? "Hook · " + tool
      : kind.equals("toolCallDraft") ? "准备工具 · " + tool
      : (kind.equals("runtimeStatus") || kind.equals("taskProgress")) ? tool
      : "工具 · " + (tool.isEmpty() ? kind : tool);
    if (item.optBoolean("summaryOnly")) label = "子任务摘要";
    String status = item.optString("status");
    if (running && active(status)) label += kind.equals("reasoning") ? " · 思考中" : " · 进行中";
    else if (status.equals("failed")) label += " · 失败";
    else if (status.equals("interrupted")) label += " · 已停止";
    else if (status.equals("completed")) label += " · 已完成";
    return label;
  }

  static String fileReportLabel(JSONObject item) {
    JSONObject counts = item.optJSONObject("counts");
    int total = counts == null ? 0 : counts.optInt("created", 0) + counts.optInt("modified", 0) + counts.optInt("deleted", 0);
    return total == 0 ? "本轮没有文件改动" : total + " 个文件有改动";
  }

  static String preview(JSONObject item) {
    String progress = ConversationContent.string(item, "progressMessage", "");
    JSONObject args = item.optJSONObject("arguments");
    String input = "";
    for (String key : new String[] {"command", "file_path", "path", "query", "url", "pattern", "description"}) {
      input = ConversationContent.string(args, key, "");
      if (!input.isEmpty()) break;
    }
    String output = ConversationContent.string(item, "aggregatedOutput", "").trim();
    if (!output.isEmpty()) {
      String[] lines = output.split("\\R");
      progress = lines[lines.length - 1];
    }
    return input.isEmpty() ? progress : progress.isEmpty() ? input : input + " · " + progress;
  }
}
