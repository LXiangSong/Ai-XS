# 会话统计后端契约（前端对接用）

本轮只改 Python 后端。原有接口和字段保留：`native/chat/usage/read`、`native/chat/thread/read` 返回的 `thread.usage`，以及 `native/collaboration/session/read` 返回的 `thread.usage` 使用同一统计结构。

新增 `usage.byModel` 数组，按 **Agent + 精确模型 ID** 分组，支持任意配置模型；旧模型保留，不能仅按当前成员配置绘制。每项包含：

- `id`：稳定分组 ID；`engine`、`model`、`modelKnown`。无法恢复的旧模型是 `model: null`，应显示“历史模型未记录”，不要填成当前模型。
- `isCurrent`、`isConfigured`、`isActive`、`historical`、`used`：当前独立选择、当前协作成员配置、存在活动执行、仅历史使用、确实执行过。
- `tokenUsage.total`：该模型累计已报告的计量 Token（输入、输出、缓存、推理等原生计数）；`totalsReported`、`totalsPartial` 标记是否缺失。空对象表示未报告，不能当成零。缓存 / 推理通常是输入 / 输出的子集，不能再次加入 totalTokens。
- `context`：最近一次实际报告的上下文快照；`contexts`：该模型每个执行来源的独立上下文。字段包括 `usedTokens`、`windowTokens`、`remainingTokens`、`ratio`（0–1）、`reported`、`stale`、`active`、`sourceId`，协作记录另含 workflow / task / attempt / participant 信息。缺失的值为 null。历史上下文是当时快照，不能把所有窗口相加或当成当前共享上下文。
- `stats`：该模型的执行次数、原生轮次、消息 / 工具 / 思考项数量、累计执行耗时、状态计数。协作重试、改派和汇总都按独立尝试计入；汇总与同模型任务归入同一个模型组。

`usage.sessionStats` 为整条会话统计：可见会话轮数与用户消息只计一次；`executions` 是所有模型实际执行总数（因此协作时可以大于 turns）。`durationMs` 为执行耗时之和，允许并行重叠，不代表用户等待的墙钟时间。

`usage.schemaVersion = 2`。兼容字段 `tokenUsage.total`、`byEngine` 仍是整个会话的已报告用量合计；`totalsPartial` 为 true 时合计不完整。`tokenUsage.last` / `contextEngine` 只代表单个最近来源，协作的完整展示请使用 byModel[].contexts，不能把它当成所有成员的上下文。

DeepSeek 同时读取 ACP 上下文通知和 DSH 原生会话记录（JSONL / Zstd），增量恢复原生保存的供应商输入、输出、缓存、推理 Token，并按精确模型 ID 分组。支持当前 DSH v1–v4 记录、尝试结算替换、重试、新原生会话以及压缩后的历史；独立和协作执行都在运行期间刷新，结束后、离线重启后仍可读取。`last` 中的上下文占用取最近请求的完整输入侧（包括缓存），不把输出或累计调用 Token 充当上下文。

若供应商和原生记录都没有计量数据，Token 仍为“未报告”，不能用 ACP 上下文占用或字符数猜算。会话轮数、消息与工具调用统计由后端生成，不依赖供应商是否报告 Token。实时事件与结束后读取使用同一去重快照，刷新或重启不会重复累计。

Claude 的原生 `modelUsage` 会按实际返回的模型 ID 拆分，支持一次执行调用多个模型；模型别名与供应商实际返回的 ID 可能不同。只报告计量、未暴露具体轮次的内部模型，其 `stats.partial` 为 true。旧会话如果只有混合模型总计，无法恢复归属的部分继续保存在 `model: null` 中，不分摊猜算。累计计数回退时保留已知值并标记 `totalsPartial`，避免旧快照使历史用量减少。

Claude 在执行前冻结原生记录和实时计量的基线，结束后及离线读取时补读原生 JSONL，恢复遗漏通知，并与同一次执行的实时计量去重。支持 `CLAUDE_CONFIG_DIR`，保留延迟写入日志的恢复边界；旧版缺少边界且无法确认完整归属的记录标记为部分数据。原生日志未提供上下文容量时保持未知，不按模型名猜测容量。

Codex 增量读取完整 rollout 中的 `turn_context` 和 `token_count`，按当时的实际模型拆分累计差值；重复通知不重复计量。上下文压缩后标为过期，直到下一份原生快照更新。分叉日志首条累计计数中无法归属的继承用量归入历史未知模型，不全部记到新模型。三种 Agent 的旧快照均会尝试补读，恢复后持久化。

尝试的模型取自当时冻结的执行配置，缺失时尝试恢复本地执行规格；二者都缺失的旧记录使用 `engine: "unknown"`、`model: null`，显示“历史 Agent / 模型未记录”。不使用当前改派成员代替历史配置。负责人生成计划的历史尝试也计入累计统计；展示过程超过 100 条不会截断统计计数，底层原始记录本身已截断时会标记 `stats.partial`。

运行期间 `thread/tokenUsage/updated` 仍提供 `conversationUsage`；协作工作进度通过已有的会话/工作流变化通知刷新上述读取接口。后端会为没有报告 Token 的启动失败和中断尝试返回模型项，前端应继续展示它们的状态和执行统计。
