# 协作后端 v2：接口与页面适配

接口基线：0.9.4-alpha。后续原生页面已接入成员、模板、预算、验证、历史分页、交接、合入与清理入口；本文保留接口与状态约定。发送、队列和中断控制的后续变化见 [执行按钮与队列](MESSAGE_QUEUE.md)。真机覆盖范围见 [发布核验](RELEASE_VERIFICATION.md)。

0.9.5 后续补充：独立与协作已新增统一队列及当前执行引导，页面也已接通；新增接口与三引擎实现差异以 [MESSAGE_QUEUE.md](MESSAGE_QUEUE.md) 为准。下面保留 v2 原有接口约定，新增能力不改变旧 message 的 next_attempt/restart 语义。

## 先做这些页面适配

| 优先级 | 页面工作 | 对接点 |
| --- | --- | --- |
| P0 | 任务卡分开显示执行状态、验证状态、整轮验收状态 | `executionStatus`、`verificationStatus`、`workflow.delivery` |
| P0 | 中断恢复面板，先检查，再核对，再手动继续 | `recovery/read` → 可选 `recovery/stop` → `reconcile` → `retry` → `resume` |
| P0 | 多选成果，预览改动与冲突，再确认合入 | `integration/preview` → `integration/apply` |
| P0 | 聊天过程增加“加载更早记录”，保持实时更新 | `conversation` 的 `before` 游标、`hasMore`、`revisions` |
| P1 | 协作模板选择，以及任务依赖、类型、验收项编辑 | `session/config`、`session/submit`、`task/config` |
| P1 | 协作运行中发送补充要求，明确何时生效 | `message`，支持下次尝试 / 中断后继续 |
| P1 | 次数、时间、已上报 Token 预算及暂停原因 | `policy`、`budgetUsage`、`pauseReasons` |
| P1 | 审核结论和问题清单，自动返工开关与轮数 | `verification.review`、`rework`、`reworkRounds` |
| P2 | 改派或重试前查看交接摘要 | `task/handoff` |
| P2 | 成果导出路径、验收后清理隔离工作区 | `artifact`、`cleanup/preview` → `cleanup` |

页面不要根据回复里的“已完成”“测试通过”等自然语言标记成功。使用后端状态和证据。

## 调用约定与兼容

下文的方法都带前缀 `native/collaboration/`。例如 `recovery/read` 的完整方法为 `native/collaboration/recovery/read`。沿用现有 RPC 通道和错误处理，不增加 HTTP 服务。

先调用 `capabilities`，无参数：

```json
{
  "version": 2,
  "templates": ["auto", "parallel", "development", "research"],
  "messageDelivery": ["next_attempt", "restart"],
  "supportsLiveInjection": true,
  "messageQueue": "native/chat/queue/",
  "tokenBudget": "reported_usage_only"
}
```

响应还含 `features` 字符串列表。旧后端不认识该方法时，保留现有页面，隐藏新增入口。原有 `create/read/save/start/pause/resume/cancel/stop/retry/reassign/respond/accept/integrate/conversation/session/*` 继续可用。新页面应改用先预览再应用的合入接口。

变更操作成功后读取返回的 `workflow`；接口只返回提示时调用 `read`。后台通知 `collaboration/changed` 携带 `workflowId`、`conversationId`，收到后刷新。部分配置接口支持 `revision` 乐观锁；过期应刷新后让用户重新操作。

## 任务状态和验收

`read {"workflowId":"w"}` 返回 `workflow`、`resources`。`workflow.tasks[]` 新增以下展示字段：

| 字段 | 含义 |
| --- | --- |
| `executionStatus` | 与现有 `status` 相同，表示调度与执行状态 |
| `verificationStatus` | `not_run` / `passed` / `failed` / `manual_required` |
| `verification` | 每项验证的结果、输出、错误、文件哈希、审核结论、依赖尝试编号与证据哈希 |
| `canRetry` | 当前是否允许创建新尝试；重试仍可能因下游正在执行被拒绝 |
| `kind` | `work` / `research` / `review` / `summary` |
| `stale` | 依赖已变化、旧成果已失效，不作为可交付成果 |
| `maxAttempts` | 此任务总执行次数上限，包括首次执行 |

执行状态建议翻译：`blocked` 等待依赖、`queued` 排队、`starting` 启动中、`running` 执行中、`waiting_input` 等待用户、`stopping` 正在停止、`succeeded` 执行完成、`failed` 失败、`interrupted` 已中断、`cancelled` 已取消、`needs_reconciliation` 待核对。

未配置自动验证时，CLI 正常结束可以是 `succeeded + manual_required`；显示“执行完成，待人工核验”。配置的文件 / 命令 / 审核任一失败，任务为 `failed`，阻止下游和整轮验收。`passed` 只表示配置的验证项通过，不代表后端证明所有需求都正确。

`workflow.delivery` 示例：

```json
{
  "status": "awaiting_acceptance",
  "verifiedTasks": 1,
  "totalTasks": 2,
  "manualRequiredTaskIds": ["t2"],
  "acceptance": null
}
```

`delivery.status`：`executing` 尚未齐备（还需结合工作流的失败 / 暂停 / 取消状态）、`verification_failed` 验证失败、`awaiting_acceptance` 待人工验收、`accepted` 用户已验收。

验收继续调用 `accept {"workflowId":"w"}`。所有任务须 `succeeded`。返回 `acceptanceReceipt`，含时间、各任务的尝试编号、验证状态及证据哈希。人工验收不等于自动测试通过，也不等于补丁已合入。重试和返工会清除旧验收。

## 模板、依赖与验收配置

保存会话默认设置：

```json
{
  "threadId": "chat",
  "template": "development",
  "budget": {"maxAttempts": 12, "maxRuntimeSeconds": 3600, "maxReportedTokens": 0}
}
```

传给 `session/config`。`session/submit` 也接受本轮覆盖的 `template`、`budget`，其余参数仍是 `threadId`、`clientRequestId`、`input`。

| 模板 | 实际行为 |
| --- | --- |
| `auto`（默认） | 职责含“审核 / 复核 / 审查 / review”时，先实现后审核；否则沿用并行分工 |
| `parallel` | 所有成员作为独立分工执行，显式保留原有并行方式 |
| `development` | 实现成员并行，审核成员等待所有实现；未设置审核职责时用最后一位成员审核 |
| `research` | 独立研究任务；可以使用已有汇总开关 |

开启汇总时，汇总任务等待此前所有任务。模板匹配是明确规则，不是自动推理最优分工。审核不能与其待审实现同时开始。若所有成员都标为审核，后端让第一位承担实现，其余审核。

精细配置有两条路径：原有 `create/save` 提交完整任务计划；或暂停且所有执行退出后，调用 `task/config`：

```json
{
  "workflowId": "w",
  "taskId": "t1",
  "revision": 7,
  "config": {
    "kind": "work",
    "dependencies": [],
    "maxAttempts": 3,
    "validation": {
      "outputs": ["report.md"],
      "checks": [["python3", "-m", "unittest", "discover", "-s", "tests"]],
      "timeoutSeconds": 60
    }
  }
}
```

允许字段：`kind/validation/maxAttempts/dependencies/reviewTargets/goal/acceptance/title/participantId/heavy/device/browser`。仅草稿、暂停、部分失败状态可编辑；存在执行中或待核对任务时拒绝。已完成任务不改写原验收配置，先通过重试创建新尝试。依赖检查存在性、自引用和循环；`reviewTargets` 必须是直接依赖的子集。

`outputs` 最多 100 个工作区相对文件路径，验证存在性并记录 SHA-256；不是内容语义验证。`checks` 最多 10 项，每项为 argv 数组，不自动解释 shell 管道和重定向；每项超时 1～120 秒。命令会真实执行，页面应展示完整命令。`maxAttempts` 为 1～10。

审核任务要求最终回复为完整 JSON（可包 JSON 代码块）：

```json
{"review":{"approved":false,"findings":["src/example.py 的异常分支缺少测试"]}}
```

格式无效、否决或修改待审文件都会验证失败。后端记录依赖的确切尝试与补丁 / ZIP 哈希。结构化审核结论仍是模型的判断，页面显示依据和未验证项。

## 合并预览与确认

`integration/preview`：

```json
{"workflowId":"w","taskIds":["t1","t2"]}
```

成功返回 `canApply:true`、`previewToken`、`taskIds`、`source/base/beforeTree/afterTree`、`changedFiles`、`patchSha256`、`patch`、`patchTruncated`、`status`。预览文本最多 131072 字符；截断时展示提示。没有净变化时 `status:"unchanged"`。

发现冲突、原项目有用户新增修改、暂存区或 HEAD 改变等，返回 `status:"blocked",canApply:false,message`；任务选择无效、仍有执行等也可能直接返回 RPC 错误。页面显示真实原因并禁用应用。

确认后调用 `integration/apply`：

```json
{"workflowId":"w","taskIds":["t1","t2"],"previewToken":"原样回传预览 token"}
```

结果为 `status:"applied"` 或 `"unchanged"`、`applied:true` 和新 `workflow`。任何选项 / 产物 / 项目变化导致 token 失效，重新预览，不能自动确认。补丁写入原项目工作区，**不会提交 Git**。

后端现在用三方合并处理完整成员树，共享祖先不会重复应用；不相交成果可分次合入，也可以批量合入。合入记录容许继续叠加本轮已管理的改动；用户新增修改会阻止后续应用。真正冲突时保留隔离成果，不覆盖原项目。

仅同一 Git 仓库、同一基线、当前成功且未过期任务可合入。非 Git 成果是 `artifact.archive` 的 ZIP 与 `archiveSha256`，供查看 / 导出和人工选取文件，不能调用 Git 合入。非 Git 依赖可在隔离快照中交接互不冲突的文件变动；同一文件的不同变动拒绝自动合并。

## 中断恢复与交接

`recovery/read {"workflowId":"w","taskId":"t1"}`，`taskId` 可省略，返回 `tasks[]`：`taskId/status/attemptId/processes/canReconcile/workspace/error/handoffAvailable`。`processes` 含 `pid/identity/alive`。此操作只检查，不重放请求、不释放资源。

页面流程：

1. `needs_reconciliation` 显示检查入口、错误、工作区、旧进程状态；禁用直接重试。
2. 旧进程仍存活时，经用户确认可调用 `recovery/stop {"workflowId":"w","taskId":"t1","confirmed":true}`。仅结束 PID 和启动身份均匹配的旧进程 / 进程组；身份不匹配的不结束，仍保留待核对状态和资源。正在回收的当前 worker 会拒绝该操作。随后刷新 `recovery/read`，`canReconcile:false` 时继续等待，不能跳过检查。
3. 用户核对文件及可能执行过的外部操作后，调用 `reconcile {"workflowId":"w","taskId":"t1","confirmed":true}`。
4. 核对成功将状态改为 `interrupted` 并释放资源，保存可恢复成果；**不会自动重新执行**。
5. 用户选择继续：`retry {"workflowId":"w","taskId":"t1"}`，然后按工作流状态调用 `resume {"workflowId":"w"}`。

进程已退出的重启恢复会尽量重新采集工作区补丁 / ZIP，保存失败转为待核对。重启后工作流暂停，不自动调用模型。没有待核对状态的普通 `interrupted/failed` 任务可以在检查成果后直接重试。

`task/handoff {"workflowId":"w","taskId":"t1"}` 返回 `handoff`：目标、验收要求、上次尝试和结果、错误、成果、验证、补充要求，以及不要盲目重放外部操作的提示。每次新尝试还保存 `handoff.json`。重试保留旧尝试，旧依赖变更会使下游成果过期。

## 补充要求的生效方式

`message`：

```json
{
  "workflowId":"w",
  "taskId":"t1",
  "clientRequestId":"本次发送的唯一 ID",
  "text":"保留现有数据，补充异常分支测试",
  "delivery":"next_attempt"
}
```

`taskId` 省略作用于整轮。单条上限 20000 字符，每轮最多 200 条。相同 `clientRequestId` 幂等，网络重试沿用原 ID。

`next_attempt` 仅保存要求，后续尚未启动的尝试会读取。返回 `requiresRestartTaskIds` 列出发送时正在运行的目标，页面显示“已保存，下次尝试生效”。已完成任务不会自动重跑，需用户重试。不要显示“已发送给运行中的 Agent”。

需要运行中成员重新读取时用 `delivery:"restart",confirmed:true`。先让用户确认中断，后端等待旧尝试退出并保存成果，然后以同一成员创建新尝试，失效相关下游。新尝试计入次数和 Token。用户暂停的工作流保持暂停，需手动恢复。没有正在运行的目标时只保存，不自动重试已完成任务。

返回 `message/workflow/notice`；首次发送还含 `requiresRestartTaskIds`。本接口只处理 next_attempt/restart；0.9.5 的当前执行引导通过 `native/chat/queue/include` 提供，能力响应 `supportsLiveInjection:true` 并给出各 Agent 的 `guidanceDelivery`，不能把中断续接误标为原生实时插入。

## 历史分页和实时过程

调用 `conversation`：

```json
{"workflowId":"w","limit":60,"participantId":"可选","taskId":"可选"}
```

返回 `entries/hasMore/before/historyPartial/omittedItems/revisions/workflow/resources`。每页最多 100 条，按时间升序。`before` 是 `[时间戳,条目ID]` 数组，下一次原样传回读取更早记录，保留相同过滤条件。不要把游标转成字符串。按 `entry.id` 去重、按 `(at,id)` 排序并前插，保留用户滚动位置。

`entries` 的 `kind` 为 `user/item/error`。成员项保留 `participantId/participant/engine/model/taskId/taskTitle/attemptId/attemptNumber/attemptStatus`；`kind:item` 的 `item` 沿用现有思考摘要、回复、命令、工具、文件等渲染。用户要求可出现在 `kind:user`，不能当作成员回复。

原会话内联回合新增 `historyHasMore/historyBefore/historyOmittedItems/delivery/budgetUsage`；仍只内联最新 100 条。`hasMore` 表示可继续分页；`omittedItems>0` 表示持久化上限已淘汰的过程，分页也不能找回；`historyPartial` 也可能来自旧数据缺失或损坏。页面区分“可加载更早记录”和“部分过程未保留”。每次尝试过程有 1000 条 / 4 MiB 上限。

实时协议保持 0.9.3 的实现：按 `attemptId + attemptSequence` 去重，按执行实例分开条目；`collaborationRevisions` / 分页的 `revisions` 用于刷新检测，不能以整轮 `status:"completed"` 判断无需重绘。该 status 是内联回合兼容字段，协作是否在运行看 `collaborationStatus/collaborationActive`。整段快照不能回退已经收到的更新事件。

## 预算、退避与返工

`policy` 示例：

```json
{
  "workflowId":"w",
  "revision":12,
  "budget":{"maxAttempts":12,"maxRuntimeSeconds":3600,"maxReportedTokens":50000},
  "rework":{"auto":false,"maxRounds":2}
}
```

预算默认：96 次、3600 秒、已上报 Token 不限（0）。范围分别 1～512、1～86400、0～100000000。提交 `budget` 时发送完整表单：遗漏字段恢复其默认值。次数包括失败、重试、改派、规划和返工。

`budgetUsage` 含 `attempts/reportedTokens/tokensPartial/elapsedSeconds/limits/stopReason/canDispatch`。时间是从本轮启动起的墙钟时间，**包含暂停时间**。运行时或已上报 Token 到限会暂停并请求停止执行；次数耗尽阻止新派发，已启动任务仍可完成。`pauseReasons` 为 `budget:runtime/budget:reported_tokens/budget:attempts`。调整预算后用户可恢复；任务级次数上限通过 `task/config` 调整。

一轮已结束时展示最后一次执行结束时的耗时，历史快照不随重启增长；再次恢复执行后重新按启动时间计算，包含中间等待时间。

单次 CLI 执行还受派发时设置的超时限制（最多 3600 秒及当时剩余的工作流时间）。运行中增大工作流预算不会延长该次执行已经设置的超时；需要时检查成果后创建新尝试。停止和预算到限也能中断后端正在执行的验证命令。

Token 来自 CLI 已上报统计，可能延迟、不完整或有统计口径差异；`tokensPartial` 时须提示。不是金额预算，也不是每个 HTTP 请求的精确硬限额。不要换算或伪造实际花费。

收到 CLI 明确上报的 429 / 503 / 529 重试状态时，同 API 来源新派发退避 1～300 秒，其他来源不受该退避影响。`waitReason` 展示来源退避或来源并发等待；`sourceBackoffs` 持久化。未上报结构化状态的 CLI 不能保证识别全部限流错误；不自动切模型或盲目重放。

返工默认关闭。开启后仅对结构化审核 `approved:false` 返工，轮数上限 0～5，并同时受任务次数、工作流预算约束。待相关执行退出后，重新排入被审任务、审核及下游；新尝试读取审核问题。格式错误等普通失败仍由用户处理。显示 `reworkRounds/maxRounds`，不能做无限重试按钮。

## 成果保留和清理

Git 成果为 `artifact.patch`；非 Git 成果为 `artifact.archive`、`archiveSha256`、`files`。路径是共享 Linux 环境路径，页面需通过已有运行环境文件通道打开 / 导出，不能当作 Android 公共下载路径。

只有整轮已结束、用户已验收、没有运行或待核对任务、成果完整时可清理：

1. `cleanup/preview {"workflowId":"w"}` 返回 `workspaces/previewToken/retains`。
2. 显示待清理的隔离目录，确认后 `cleanup {"workflowId":"w","previewToken":"...","confirmed":true}`。
3. 返回 `cleanedAttemptIds/workflow`；对应 `artifact.cleanedAt` 标明工作区已清理。

只删除属于这些尝试的 worktree / 快照，不删除原项目。保留补丁 / ZIP、会话过程、验证报告、用量和交接数据。清理前验证导出存在及可用的证据哈希；进程尚存活或成果不完整时拒绝。当前不自动定期删除，不提供磁盘释放字节数估算。

## 验收与已知边界

本地自动测试覆盖：共享祖先与连续合入、真实冲突和用户改动保护、预览失效、合入回执异常恢复、非 Git 交接与删除、验证命令与超时、严格审核、审核依赖顺序、有限返工、恢复成果采集、预算暂停、DeepSeek 阻塞回合停止、补充要求幂等与新尝试交接、历史分页、清理后历史保留。

完整检查命令：`python android/tools/test_all.py --offline`。新增专项测试：`python -m unittest discover -s android/tests -p test_collaboration_backend.py`。完整检查包括 Python、Java 单测、Android Lint、签名 Release 构建和签名验证。

页面已接入后，仍需持续在真机验收：三引擎并发流式内容、加载历史时的新事件、应用进后台 / 服务重启、取消时未结束的命令、真实网关限流、真实项目冲突及导出。自动测试使用本地模拟 CLI，不代表已经覆盖全部手机和真实 API 行为。

工作区仍不是限制任意 shell 的安全沙箱；非 Git 合并按文件处理，不做同一文件内部的三方文本合并。仍未实现自动最优分工、精确金额预算和长期压力测试。
