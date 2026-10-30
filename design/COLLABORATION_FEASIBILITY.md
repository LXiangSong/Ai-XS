# 协作实现可行性核验

日期：2026-10-30。结论：技术路线可实现；现有 Ai-XS 尚未具备完整协作运行能力，不能只移除单 Agent 限制就上线。

## 实测证据

使用手机上已安装的真实 CLI，对接仅监听本机的模拟 API。模型名 `probe-model-a` / `probe-model-b` 是测试参数，不是真实提供商型号。接口先保持两个请求同时挂起，确认两个实例都存活，再结束实例 A，放行实例 B 的响应并检查成功完成。测试不读取现有 API 密钥，不向真实模型发送请求。

| CLI | 运行位置 / 版本 | 两个模型请求同时未完成 | 结束 A 后 B 成功完成 |
| --- | --- | --- | --- |
| Codex | Termux，0.164.0 | 通过；两个 `/v1/responses` 请求，模型参数分别为 A / B | 通过；B 收到 `turn/completed`，状态 completed |
| Claude Code | Termux 管理的 Ubuntu，2.1.312 | 通过；两个 `/v1/messages?beta=true` 请求，模型参数分别为 A / B | 通过；B 返回成功 result，进程退出码 0 |
| DeepSeek Harness | 同一 Ubuntu，0.2.1-rc.2 | 通过；两个 `/v1/chat/completions` 请求，模型参数分别为 A / B | 通过；B 返回 end_turn 和预期内容 |

Codex 使用两个独立 `CODEX_HOME`、不同原生会话；Claude 使用两个 `CLAUDE_CONFIG_DIR`；DeepSeek 使用两个 `DSH_HOME`、原生会话和 provider patch。共享程序安装与 Ubuntu，不复制操作系统。测试按 Agent 分组执行，每组两个实例，不同时启动六个实例。

在临时 Git 仓库建立两个 worktree，分别修改同一路径的文件：两个修改互相隔离，主工作区保留原内容。此项只验证文件工作区隔离，不等于已实现自动集成或冲突解决。

此外运行现有 Agent 生命周期、provider 配置和共享会话测试，共 22 项通过。这些测试覆盖现有行为，不能代替协作调度器的验收。

原始脚本和结果保存在本机私有 `.state/collaboration-research/`：`probe_cli_isolation.py`、`probe-{codex,claude,deepseek}-result.json`、`probe-worktree-result.json`。它们未被打包进 App，也未发布。

## 三种 Agent 各用两个模型配置的组合核验

在上述单 Agent 双实例实验后，追加一个包含六位参与者的组合实验。使用同一 Ubuntu 中的真实 CLI、独立配置目录和原生会话；Codex 调用 Termux 的 0.164.0 二进制。测试专用队列设置全局最多两个执行任务，所有接口均为本地模拟 API。它不是现有 App 调度器的测试，因为该功能尚未实现。

| 子任务 | Agent / 模型配置 | 前置任务 | 结果 |
| --- | --- | --- | --- |
| 规划 | Codex / probe-codex-a | 无 | 通过 |
| 需求分析 | Claude / probe-claude-a | 无 | 通过 |
| 测试准备 | DeepSeek / probe-deepseek-a | 规划 | 通过 |
| 实现 | Codex / probe-codex-b | 规划、需求分析 | 通过 |
| 审核 | Claude / probe-claude-b | 实现、测试准备 | 通过 |
| 汇总 | DeepSeek / probe-deepseek-b | 审核 | 通过 |

六项全部完成，实际观察到两个 worker 同时执行、两个模型请求同时等待响应。检查了每个请求的目标模型、任务与公共项目标识；前置任务完成后才派发，下一任务的实际 API 输入包含指定前置产物。其他参与者的私有上下文标记没有串入，输出匹配各自任务，六个会话 ID 和进程 ID 各不相同，配置文件未互相改写。

“规划 / 实现 / 审核”等是本次测试任务标签，模型回复为接口预置的产物标记；没有让真实云模型生成计划、修改代码或判断审核质量。此项证明三种 CLI 的多模型配置与任务交接可以组合起来，不是对真实智能分工效果的验收。

用量观察：Codex 返回分线程 Token 数据，Claude 返回各执行的 usage；DeepSeek 的 ACP `usage_update` 返回上下文占用。三者都可关联执行实例，但后者不能直接当作累计计费 Token 汇总；协作统计须保留数据来源与缺失状态。

本地接口下本轮 DeepSeek 任务约 17–19 秒，包含冷启动与初始化；其他任务有等待搭档请求的时间。这不能用来比较模型速度，提示实例复用与按需回收仍需设计和真机测量。

可审阅结果：[组合核验记录](collaboration-multi-model-verification.json)。原始脚本、事件结果和日志保存在 `.state/collaboration-research/probe_multi_model_workflow.py`、`multi-model-workflow-result.json`、`multi-model-workflow.log`。本次仍未验证 cooper-api 的真实模型兼容性、工具执行与限流，也未向已安装 APK 接入协作功能。

## 对当前代码的判断

| 当前实现 | 影响 | 必须完成的调整 |
| --- | --- | --- |
| `RuntimeManager.guest()` 设置 `AI_XS_SINGLE_AGENT=1`；`EngineManager._activate()` 拒绝另一活跃 Agent | 不同 Agent 任务无法按设计并行 | 加入统一调度，再由能力与名额控制并发；保留按需启动 |
| `AppServer` 是单一进程管理对象；Codex 审批 ID 未加 worker 命名空间 | 不能直接创建多个对象后混用事件和审批 | 建立 worker 注册与路由；使用 workerId + 原生 ID 关联请求、终止、事件和用量 |
| `ProviderManager` 每个 Agent 仅选择一个当前配置，并生成共用文件 | 两个参与者不能安全同时使用不同 API 来源或可变配置 | 每次尝试固定 provider / 模型 / 权限快照，使用独立目录和启动环境；修改设置不改写运行中尝试 |
| Claude / DeepSeek 已按 thread 保存 runs，模型可随执行指定 | 有可复用的适配基础；并非从零实现进程协议 | 保留线程与进程控制，增加参与者、配置版本和工作区路由；共享缓存按配置身份分开 |
| `SharedConversations` 一个可见会话只有一个当前 binding | 不能把多个并行子任务都放入这个 binding | 可见主会话保留；协作子任务独立 binding，汇总通过版本化交接包进入主会话 |
| DeepSeek 找不到请求模型时当前会回退到默认模型 | 协作任务可能由错误模型执行，界面却仍显示原分工 | 协作执行必须严格校验并报错；禁止静默换模型 |
| 手机控制 / 浏览器使用同一设备状态 | 两个 Agent 的点击与输入可能互相干扰 | 调度器对控制会话加租约，同一时间只允许一个操作者；读取也需考虑状态一致性 |

这些是现有 App 的软件改造项。真实 CLI 的双实例实验已经排除了“每种 Agent 天生只能运行一个实例”这一阻碍。

## 确定采用的方案

- 一套共享环境与安装，多个按需启动的执行实例；每个尝试固定配置，任务指定参与者。
- 第一版优先使用独立进程实现隔离。Codex 单进程复用多个会话可在后续单独验证后优化，不作为首版依赖。
- 默认全局最多两个执行任务、一个重任务；规划和汇总也占名额。参与者数量可以更多，多出的任务排队。
- 只向子任务提供必要背景和依赖产物；主会话保存完整记录与聚合用量。不同 CLI 通过交接包延续上下文，不声称原生会话格式互通。
- Git 项目并行写入使用 worktree，串行集成；非 Git 共享目录只允许一个写入任务。
- 同一配置的空闲 worker 可以在资源允许时短暂复用，减少 DeepSeek 等 CLI 的冷启动成本；更换 provider / 工具范围时重新校验，不能为了复用混用配置。
- 原生 Material 页面保留；不需要改为 WebView，也不需要为每个角色安装一套 Ubuntu。

## 本次核验的边界

已确认真实 CLI 可以接受独立配置并同时发出不同模型参数的请求，且一个进程结束不妨碍另一个完成。它不等于三个 Agent 的任意模型都兼容，也不证明第三方 API 允许所选并发量。

本次在同一部手机的 Termux / Ubuntu 测试环境执行，未在已安装 APK 的私有运行时内注入测试。尤其 Codex 使用 Termux 0.164.0，不能作为 APK 内另一版本的验收。进程终止测试也不能替代 App 中暂停、取消、权限批准和重试的完整联调。

尚需实施并验收：全局调度与持久化、worker 事件与审批隔离、真实 API 模型与工具兼容性、同网关限流、独立 / 协作切换、后台中断恢复、长时间资源压力、补丁审核与集成。并发上限是否可提高到 3，应由 APK 真机压力结果决定。

因此可以按此方案进入实现；“所有功能已在 App 中稳定可用”要等上述验收完成后才能确认。
