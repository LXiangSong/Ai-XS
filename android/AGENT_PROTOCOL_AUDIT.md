# Agent 事件适配核查

本次范围：Codex、Claude Code、DeepSeek Harness 的实时思考、正文、工具生命周期、最终结果、项目文件清单和耗时。核查结合官方公开源码、Ai-XS 本地实现及真实 CLI 的延迟接口测试；不等同于对项目全部功能、每个远程模型或所有历史 CLI 版本的认证。

## 已确认并修复的问题

| 问题 | 影响 | 修复 |
| --- | --- | --- |
| Codex `aggregatedOutput` 等字段可以为 `null` | 第一段输出追加失败，或终态空值覆盖已收到的内容 | Python、Android、协作记录统一处理空值 |
| Codex 时间字段可以为 `null` | 最终会话保存失败，缺失结束事件和统计 | 保留已有有效时间，缺失时记录事件接收时间 |
| 忽略原生精确耗时，时间精度和单位处理不一致 | 重新进入会话后耗时缺失，或小数秒被截断 | 优先保留有效 `durationMs`，统一秒/毫秒和 ISO 时间，界面读取持久化值 |
| Codex 动态工具结果使用 `contentItems` | 工具完成了但结果内容没有显示 | 展示文字结果，媒体内容使用类型提示，避免输出原始媒体数据 |
| Claude 流式工具参数被视为已执行操作 | 失败重试可能留下假完成工具卡 | 草稿与执行区分，放弃的草稿标记未执行 |
| Claude `task_updated` 没有处理 | 后台任务只发送终态 patch 时，卡片无法及时结束 | 合并 patch，保留省略字段，处理终态和结果/错误 |
| DeepSeek ACP 计划更新未投影 | 计划步骤不能及时显示 | 转为统一计划事件 |
| 文件结果依赖模型自行描述或编辑工具 | shell、构建等方式生成的文件可能漏报 | 三种 agent 共用有界的项目文件前后清单，并持久化到会话 |

## 官方源码依据

- [Codex item 定义](https://github.com/openai/codex/blob/main/codex-rs/app-server-protocol/src/protocol/v2/item.rs)：命令输出和耗时为可空字段，动态工具结果位于 `contentItems`。
- [Codex thread 时间定义](https://github.com/openai/codex/blob/main/codex-rs/app-server-protocol/src/protocol/v2/thread.rs) 与 [事件注册](https://github.com/openai/codex/blob/main/codex-rs/app-server-protocol/src/protocol/common.rs)：区分开始、完成、增量和进度事件，允许缺失时间数据。
- [Claude 官方 SDK 消息解析](https://github.com/anthropics/claude-agent-sdk-python/blob/main/src/claude_agent_sdk/_internal/message_parser.py)：`task_updated` 可以承担独立的任务终态通知，应按 patch 合并；[官方 CLI transport](https://github.com/anthropics/claude-agent-sdk-python/blob/main/src/claude_agent_sdk/_internal/transport/subprocess_cli.py) 明确启用 stream-json 和 partial messages。Claude Code 仓库没有完整公开核心 CLI；此处对照的是公开 SDK，不把 SDK 当作 CLI 核心源码。
- [DeepSeek ACP 更新转换](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/acp/acp/src/updates.ts)：标准 thought/message chunk 从已提交消息生成；[原始 assistant stream](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/core/agent-loop/src/assistant-stream.ts) 提供独立 attempt、revision、增量及提交/放弃事件。Ai-XS 的伴随插件需要使用该通道，并抑制原 ACP 重放。

下载源码的 blob SHA 和本地核查文件记录于 `.state/upstream-audit/source-index.json`。上游主分支用于确认协议语义，实际兼容性仍以安装版本实测为准。

## 能力边界与待覆盖项目

- 模型和服务端必须实际提供流式内容与思考字段。界面不能恢复未返回的思考，也不生成模拟思考。
- 当前 Claude stream-json 没有原始终端 stdout 的逐段事件；可显示工具调用、参数与执行进度，输出在工具结果返回时显示。Codex unified exec 的部分首段输出也可能只存在于完成快照。
- DeepSeek 伴随插件依赖 CLI 的进程内扩展点。标准 ACP 是降级路径，但降级后的 chunk 不代表逐 token。版本切换后需要重新验证扩展点，不能承诺所有历史和未来版本同样支持。
- Ai-XS 协作执行与 CLI 内置子任务分别处理。现已增加 Codex 子线程订阅、快速完成快照补读、DeepSeek 显式父 session 路由及子任务生命周期；只接受协议中的真实父子关系，不能按同目录或同模型猜测归属。三种已安装 CLI 均已通过 App 内原生子任务测试。
- 当前安装 Codex 的 `spawn_agent.model` 还有 CLI 自有白名单；继承父模型与显式覆盖不是同一条校验路径。自定义模型 ID 的覆盖拒绝已通过本机接口实测并保留原始结果，不能声称 App 已解除 CLI 白名单。Ai-XS 自有协作执行按配置分别启动任务，不依赖原生 `spawn_agent` 的模型覆盖。
- 显式覆盖模型的定向测试分别验证模型请求、正文、生命周期和分模型用量；该样本的 CLI 请求没有携带工具定义，不能把它算作工具执行验证。工具执行使用另一条继承模型的原生子任务用例验证。
- Claude 根据 CLI 帮助探测可选 `--include-hook-events`；当前安装版本的 PreToolUse 开始、结束和输出已实测。该版本没有把所有子任务正文逐 token 暴露给 stream-json：工具进度与最终 task 摘要可见，摘要明确标记，不伪造未收到的思考。
- 任务唤醒锁原来只覆盖环境安装，现已按独立回合和协作 attempt 持有，并用后台状态快照清理异常退出的执行；快照期间新启动的任务不能被旧快照清掉。Android 实际唤醒锁的开始、状态核对释放已验证。此测试为受控生命周期，不能证明所有厂商电源策略、无限时后台运行或系统杀进程后的恢复可靠；隔离 CLI 测试不经过正式 NativeEngine，不能把它当作实际后台保活证明。
- 文件清单记录所选项目在本轮期间的净变化，包含命令生成文件，不证明修改的排他归属。扫描限额、依赖/缓存目录、项目外文件及目录符号链接的范围见 `REALTIME_PROGRESS.md`。大文件通过元数据识别，部分扫描明确提示不完整。
- 延迟接口测试中的不同模型 ID 验证路由没有按名称硬编码，不验证同名远程模型的真实能力。实际模型和第三方接口仍需各自返回合规事件。

## 验证记录

自动测试、App 内 CLI 矩阵、文件持久化及耗时对照结果保存在 `.state` 下相应报告。测试使用本机延迟接口和临时项目，不使用现有第三方账户密钥。最终安装产物及验证数量以本次完成记录为准。

实际 App 内 CLI 版本：Codex `0.166.0`、Claude Code `2.1.314`、DeepSeek Harness `0.2.1-rc.5`。另一个共享环境的 DSH `0.2.1-rc.2` 也通过三种协议的延迟接口验证。

上一批构建号 `47`：172 项 Python 测试、49 项 Java 测试、release lint、构建和签名检查通过。App 内 10 组协议/模型 ID 用例经复测均通过，原生界面的文件清单展开折叠、动态工具结果和小数秒精度也通过检查。曾超时的 DeepSeek Responses 用例及原始失败记录均保留，没有用复测结果覆盖原始证据。

耗时样本：Codex 原生 `durationMs=5281`，观察到事件间隔约 `5192 ms`；DeepSeek `durationMs=33322`，事件间隔约 `33326 ms`。会话重新读取后耗时及文件清单保持一致。接口和事件接收之间存在少量处理/调度差异，原生提供的有效精确耗时优先于本机显示缓存。

验证汇总：`.state/agent-protocol-audit-verification.json`；App 矩阵：`.state/app-live-files-result.json`；最初批次与复测分别保存在 `.state/app-live-files-initial-result.json` 和 `.state/app-live-files-rerun-result.json`。


## 构建 48 的继续核查

按本机安装 Codex 生成的 schema，逐项登记 85 种通知、19 种 ThreadItem，见 `PROTOCOL_EVENT_COVERAGE.json`。会话运行事件、管理通知、未启用的语音/RTC 与平台不适用事件分开记录；这不是 85 个产品功能均已启用的声明。

追加修复：

- Codex 原生子线程的命令、推理、正文、Hook、计划及用量汇入拥有它的主回合；子任务完成不能结束父回合，父模型先结束时等待仍运行的子任务。停止会转发到活动子线程。用量独立保存，重启后恢复，删除会话同时清理该缓存。
- Codex 的等待工具只能观察已知子任务，不能把无关会话认领为孩子或覆盖原始派发关系。主线程模型切换后按累计用量差额记入对应模型，再与子任务合并，避免把旧模型账单归给当前模型。子任务恰好结束而拒绝停止请求时，仍继续停止主任务，并显示未确认信息。子线程在首回合开始前的状态/警告通知不再创建永不结束的活动回合，防止父任务一直等待。
- DeepSeek 内置子 session 的瞬时流、工具进度、stdout、结果和结束原因接入同一会话；识别结构化失败，不把失败当成功。修复扩展访问未注入服务导致子任务请求前失败的问题。
- Claude 单个 content block 快照的索引每次可能从零开始；按已有内容块核对，防止正文覆盖思考。主回复结束时等待登记中的后台任务；只返回摘要的子任务显示为“子任务摘要”。
- 子任务按实际模型统计工具、推理及输出，并持久化到独立和协作记录。原生没有报告的子任务耗时、模型或 context 不编造，保留 partial/未知归属提示。
- 回合开始前的子线程运行提示保留显示，但不额外计为一次执行。子任务统计优先使用原生精确 `durationMs`，避免用整数秒的开始/结束时间覆盖毫秒耗时。
- 运行提示、配置警告、认证恢复、模型验证、审核等待、MCP 启动状态和工具失败终态补齐。工具结果、Hook 提示片段、审查结果、图片生成路径、读取图片和等待操作具有可读内容与标签；媒体数据不作为长串编码展示。

验证使用 App 内实际 CLI、正式 NativeHost/适配模块及隔离的延迟接口，不使用现有用户密钥。不同模型 ID 验证没有写死模型名，不证明所有远程模型都支持相同能力。三种子任务的成功结果在 `.state/app-coverage-native-children-passed.json`；失败与测试脚本修正记录另存保留。Android 唤醒锁受控测试在 `.state/app-wake-active.log`、`.state/app-wake-after.log` 和 `.state/app-wake-lifecycle.log`。

191 项 Python、54 项 Java、release lint、构建和签名检查通过。普通会话的 10 组协议/模型 ID 矩阵通过，原生文件清单展开折叠、工具结果、生成图片路径及小数秒耗时检查通过。最终安装哈希与子任务定向复测以 `.state/agent-protocol-audit-verification.json` 的构建 48 记录为准。没有推送或发布 GitHub。
