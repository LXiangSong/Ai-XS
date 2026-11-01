# 任务执行中的后续消息：排队、编辑、删除、引入

适用版本：0.9.10-alpha（队列自 0.9.5 起提供）。独立与协作共用持久化队列和单一执行按钮。

0.9.10 修正：自动接力严格检查首个待处理项，失败或待核对的队首阻止后续项；旧版本保存的暂停状态在升级后仍保持。队列状态刷新保留协作流式序号、已加载的历史分页和较新的本地回合，同一会话重载不因缓存未命中退回新会话页面。

编辑待发项前，输入框已有草稿时保留原队列项并提示先处理草稿。若在取消排队请求的等待期间新增输入，恢复消息时会同时保留新增文字、附件和引用。

独立即时发送使用 `clientRequestId` 保存发送意图与回执，重复调用即时入口或队列入口返回原回合及 `replayed:true`，不会生成另一个任务；资源占位回合开始执行后回执指向实际回合。已确认回执保留最近 200 条；未确认回执在服务重启后继续要求核对，不自动重发。无请求编号的旧调用无法提供请求去重。

## 页面行为

输入区只保留一个执行按钮。有文字、引用或附件时显示发送；执行中输入为空时显示停止方块；停止后若没有待发消息且输入为空才显示继续，有待发消息时按钮显示为等待自动接力。文字、附件增删即时更新按钮。空闲且输入为空时按钮禁用。任务详情不再保留另一套行为不同的停止入口。

执行中发送的消息进入输入区上方的待发条，最多显示三条扁平标签，每条直接带“编辑 / 引用 / 删除”，并显示暂停 / 继续；点按标题打开完整队列。队列刷新只更新待发条、执行按钮和状态说明，不再重建消息列表；只有回合或消息内容变化时才更新对应回合，避免待发条出现、消失或自动接力时页面闪动。没有执行中的任务时新消息直接调用即时执行接口，不进入待发队列；只有真实运行中的任务才需要排队。本会话当前执行结束且资源允许后按先后顺序发送。**手动停止或中断后，已有普通待发消息也会自动按顺序接力，不再需要先点“继续”**；只有显式暂停队列、结果未知或原任务没有普通待发消息时，才使用空输入的继续按钮。编辑、删除或引用不会暂停队列，剩余消息继续自动发送。停止旧协作后发送新任务会结束旧轮的剩余派发，保留原成果与历史，再开始新轮；结果未知时仍须先核对。

停止会中断当前执行，并等待退出和成果保存完成；停止收尾时不允许重复继续。独立模式使用原 Agent、模型和同一原生会话发起继续回合，`continuationOf` 指向原中断回合。协作保持原工作流、分工和已经完成的成员，未完成成员从保留成果和交接资料开始新的执行尝试，遵守原预算与次数限制。当前实现没有冻结推理现场的暂停能力，已发生的操作不会回滚。

普通待发消息优先自动接力：停止后若有待发项，会先执行队列，不再插入“继续原任务”的受保护请求；若没有普通待发项，空输入继续仍然可用。若此前手动暂停了待发队列，自动接力停止，点击继续原任务也不会恢复这个队列。中断记录跨服务重启保留；只有确实需要续做原任务且没有待发项时才继续。手动停止后的等待时间不计入协作运行时间预算。资源不足时显示等待原因，普通待发消息仍可编辑、删除，资源恢复后自动发送。

按钮语义与中断协议依据见 [Codex 源码参考](CODEX_EXECUTION_REFERENCE.md)。空输入点击继续是本 App 按用户要求增加的操作，Codex TUI 的空输入提交本身不会自动发出“继续”。

- **编辑**：界面直接取消这条排队，把完整文字和图片附件放回输入框；重新发送时作为新消息排队。后端请求级 `queue/edit` 仍保留给协议调用。
- **删除**：从待发队列移除，不影响正在执行的任务。
- **引用**：直接调用 `queue/include`，把消息提供给当前执行，用于调整方向或补充资料；成功后不再作为下一项任务发送，不再弹出二次确认。
- **暂停队列 / 恢复发送**：只控制下一项消息的派发，不中断当前任务。
- **核对并重试**：发送失败或结果未知时，先检查会话与成员回执，再决定是否重新排队。

消息已进入发送或引入阶段时，禁止编辑、删除，避免出现界面删除成功而 Agent 仍收到旧内容。引入与完成同时发生时以 Agent 的实际回执为准，失败不会伪装为成功。批量协作引入部分成功时保留逐成员结果，不会自动重复发送给成功的成员。

资源不足、独立执行名额不足、同项目互斥时，消息保留为可编辑的排队项并显示等待原因，资源恢复后自动发送。队列跨服务重启保留；显式暂停的队列继续暂停，普通干净队列自动继续，发送中或引入中的未知结果转为待核对并暂停，不能自动重放。

## 三个 Agent 的实际能力和参考来源

| Agent | 本版引入方式 | 核对的实现 / 协议 |
| --- | --- | --- |
| Codex | `turn/steer`，携带 `expectedTurnId` 和 `clientUserMessageId`，校验当前回合 | 本机 Codex 导出的 `v2/TurnSteerParams.json`；[官方 app-server 协议](https://github.com/openai/codex/blob/main/codex-rs/app-server/README.md) |
| Claude Code | stream-json `control_request/interrupt`，等当前结果后，向同一原生会话写入补充 user 消息，保留同一 App 回合 | 项目现有 `claude_bridge.py` 的 `turn/steer`、`guidance` 续接实现；[官方 Streaming Input 文档](https://platform.claude.com/docs/en/agent-sdk/streaming-vs-single-mode) |
| DeepSeek Harness | `session/cancel`，等待当前 ACP prompt 结束，然后向同一 `sessionId` 发送补充 prompt，保留同一 App 回合 | 本机安装的 `@deepseek-ai/dsh-acp@0.2.1-rc.2` 与 `dsh-api-session-controller` 的发布源码；[官方仓库](https://github.com/deepseek-ai/deepseek-harness) |

DeepSeek 的原生控制器支持 `mode:"steer"` 调用 `agent.steer(message)`，默认则调用 `agent.followup(message)`；**当前 ACP 通道的 prompt 实现拒绝同一会话并发 prompt**，并使用 `agent.followup` 入队。本版不把第二个 ACP prompt 硬塞到进行中的请求，也不把网站控制器的能力冒充为 ACP 的能力。

DeepSeek 通过已用的 ACP 通道取消至当前请求结束再续接，不启动新 worktree、不改派模型、不增加协作 attempt。已经发生的文件修改或外部操作保留；中断不是回滚。Claude Code 安装包是原生二进制，不能宣称已检查未公开的核心源码；本版核对了现有适配器和官方公开协议文档。

协作 worker 新增带回执的引导控制通道。后端逐个收集成员接收结果；控制请求不会堵住停止命令。后续未启动阶段从持久化补充要求中读取相同信息。初始化、停止、验证命令阶段或已结束成员可能拒绝引入，错误和未知结果会如实保留。

## RPC 接口

### 当前执行中断与继续

`native/chat/execution/interrupt`、`resume`、`read` 都带共享会话 `threadId`，响应与 queue/list 一致，包含 thread/entries/paused。interrupt 可带 `turnId` 或 `workflowId` 来校验当前任务。旧 execution/pause 仅保留为中断的兼容别名，不提供冻结式暂停；queue/pause 仍只暂停队列派发。

thread 新增 `executionControl`，状态为 stopping/interrupted/resuming/running/failed/needs_reconciliation/cancelled/completed/superseded；包含 mode、turnId 或 workflowId、原配置、继续请求编号和错误。旧 pausing/paused 状态自动迁移，不触发重发。completed 表示原任务在中断请求生效前已结束；superseded 表示用户已发送新内容，空输入不再重复继续旧任务。通过 `native/queueChanged` 通知刷新。重复中断、继续请求不会重复创建继续任务。原继续请求结果未知时要求核对并明确确认重试，不能通过空输入继续按钮直接重发。

独立继续使用队列中的受保护条目 `executionResume:true`，在队列没有普通待发项或显式暂停时创建并优先发送，不可编辑、删除或引入；它仍受资源调度限制。若普通待发项可自动接力，会先执行普通队列，不插入继续请求。发送失败允许重试，结果未知必须核对。能力响应包含 `executionControl:"native/chat/execution/"`、`supportsExecutionInterrupt:true`、`supportsExecutionPause:false`。协作面板的 resume 也可继续由执行按钮中断的工作流。

### 消息队列

统一前缀 `native/chat/queue/`。所有操作使用共享会话 `threadId`，与当前独立 / 协作显示模式无关。

### 提交

```json
{
  "method":"native/chat/queue/submit",
  "params":{
    "threadId":"aixs-...",
    "mode":"independent",
    "clientRequestId":"这次发送的唯一 ID，网络重试时复用",
    "input":[{"type":"text","text":"补充材料，先排队"}],
    "engine":"codex",
    "model":"实际模型 ID",
    "effort":"high"
  }
}
```

`mode` 为 `independent/collaboration`，省略使用会话当前模式。协作项可携带 `template/budget`，保存排队时的成员、汇总开关、模板与预算配置；开始执行时使用届时已有的会话历史。API 来源仍按实际派发时的执行配置解析，不把密钥写进队列。

`input` 支持 text/localImage，最多 32 个内容块，文本总长不超过 28000 字符。最多 100 条待处理消息，保留最近 200 条已处理回执以支持幂等。消息已删除的请求编号仍返回原删除回执，不会重新生成任务。

响应包含 `entry`、`entries`、`thread`、`paused`、`queued` 和提示 `message`。条目有 `id/clientRequestId/input/mode/status/revision/createdAt/updatedAt/error`，可能带 `waitReason/receipt/targets/receipts`。实际派发成功后 `receipt.turn` 或 `receipt.workflow` 指向创建的回合 / 工作流。

原有 `native/chat/turn/start` 和 `native/collaboration/session/submit` 在同会话繁忙或已有待发消息时也会进入队列，返回 `queued:true` 与 `entry`，不再一律拒绝。空闲时旧入口保持原有即时提交语义；界面在无运行中任务时也走这两个即时接口，仅在运行中发送时使用 queue/submit。

### 查询与管理

| 方法 | 参数（都带 `threadId`） | 行为 |
| --- | --- | --- |
| `list` | 无其他必需项 | 返回全部有界条目、thread 和 paused |
| `edit` | `messageId,input,revision?` | 仅排队 / 失败项可编辑，保存后排队；未知结果须先核对 |
| `delete` | `messageId,revision?` | 移除排队 / 失败 / 未知项，不停止正在执行的任务 |
| `include` | `messageId,revision?,taskIds?` | 将排队消息引入当前执行；协作可指定运行中的子任务，省略则本轮全部运行中成员及后续阶段 |
| `pause` / `resume` | 无其他必需项 | 暂停 / 恢复自动发送 |
| `retry` | `messageId,confirmed?` | 已失败项重新排队；未知结果必须 `confirmed:true`，确认没有重复执行 |

`revision` 是消息条目版本，过期更新被拒绝。队列自动派发与编辑、删除、引入共享同一服务锁；派发抢先取得条目后，后续编辑会失败并要求刷新。

`include` 首次响应表示已开始处理，并不意味着所有成员已经接收。重复调用同一条目 include，不重复投递。监听变化后读取最终状态及 `receipts`：

```json
{
  "status":"included",
  "receipts":[
    {"mode":"collaboration","taskId":"t1","attemptId":"a1","engine":"codex","status":"accepted","delivery":"native_steer"},
    {"mode":"collaboration","taskId":"t2","attemptId":"a2","engine":"deepseek","status":"accepted","delivery":"interrupt_continue"}
  ]
}
```

`accepted` 表示适配器接受了该控制请求；不表示任务完成，也不证明模型已经落实全部要求。`unknown` 表示超时未能确认，不能自动重发。若存在部分失败，保留成功回执，页面显示需要核对。已完成成员不会被自动重新执行；指定 `taskIds` 时，补充要求只交给这些任务的后续尝试。

### 状态和事件

| status | 页面含义 |
| --- | --- |
| `queued` | 排队，可编辑、删除、引入 |
| `dispatching` | 正在提交，暂不可编辑 / 删除 |
| `including` | 正在引入，等待实际回执 |
| `sent` | 已提交为下一项任务，查看 receipt 对应的回合 / 工作流 |
| `included` | 引入已接受，不再派发为下一项任务 |
| `failed` | 失败，保留消息与错误，用户决定如何处理 |
| `needs_reconciliation` | 发送结果未知，需核对后继续 |
| `deleted` | 已从队列移除，不执行 |

`native/queueChanged` 通知参数含 `threadId`，收到后读取队列状态；界面只合并队列与执行控制字段，不重新渲染整页消息。共享 thread 新增 `messageQueue`、`messageQueuePaused`；队列界面按这些字段展示。`sent/included/deleted` 不在待发列表显示，真实消息和执行过程留在原会话。

`native/collaboration/capabilities` 保留版本 2，新增 `messageQueue:"native/chat/queue/"`、`supportsLiveInjection:true` 和 `guidanceDelivery` 映射。旧 `native/collaboration/message` 的 next_attempt/restart 行为不变，队列 include 是新增的当前执行引导入口。

## 验证范围

回归覆盖三引擎同回合引导、DeepSeek 取消后同 session 续接、协作逐成员回执和不增加 attempt、成员定向引导、失效权限请求清理、FIFO、编辑版本冲突、删除不执行、输入附件保留、引入幂等、资源等待时可编辑、正常完成/停止后自动接力、干净重启自动继续、显式暂停保持、未知结果不重放。

中断 / 继续另覆盖三引擎原生会话复用、停止后普通队列自动接力且不要求继续、直接新输入不排队、显式暂停队列不被自动恢复、协作原工作流与已完成成员不重跑、中断前文件成果保留、次数限制、重启不自动继续原任务、资源等待、完成与中断竞争、等待时间预算及旧状态迁移；Java 单元测试覆盖执行按钮状态切换和待发消息自动接力时的等待状态。

这些测试使用本地模拟 CLI 验证真实适配器和私有管道，不会调用真实模型 API。真机还应确认：输入键盘与队列面板布局、后台恢复、长命令中断、真实网关超时和各 CLI 版本的取消行为。
