# Ai-XS

Ai-XS 是独立的 Android 原生 Agent 工作空间，与 AI-Web 分开维护。
UI 工程使用 Gradle、Android Gradle Plugin、Google Material Components 和 Material 3。
主界面提供 Agent 切换、会话、项目、API/MCP 设置、环境管理和手机工具。

初始化仅在 **设置 → 运行环境**；未初始化时提示“去设置 / 稍后”，没有自动初始化入口。
Codex、Claude Code、DeepSeek 共用一套私有 ARM64 Linux 环境。
新安装分别配置官方 API 或第三方 API，不继承其他 App 的账户。

构建及初始化说明见 [Android 文档](android/README.md) 与 [安装指南](android/INSTALLATION.md)。
当前是 `0.8.7-alpha`：Codex、Claude Code、DeepSeek 可接手同一条 App 会话，保留项目、历史、工具结果与草稿。
用户消息支持复制、引用、编辑并重发，编辑前的完整后续保留为独立分支；所有 Agent 回复均支持复制和引用。
跨 Agent 用量按私有会话快照持久汇总，切换不会清空历史统计；实际上下文在交接后更新。
模型思考选项按能力目录显示，支持的 Codex 模型可选择 MAX。
设置中的扩展库支持从 GitHub、本地 ZIP / SKILL.md 导入 Skills 和插件，也可让 Agent 调用扩展工具完成安装；每项可分配给一个或多个 Agent，不支持的原生插件组件会显示具体原因。
权限弹窗显示工具、目标路径、命令、工作目录和申请说明，保留编辑参数与协议特有详情；长内容可滚动查看、选择复制。
设置 → Agent 更新可检查三个 Agent 的官方最新版本并更新；有运行中的任务时会拒绝更新，空闲服务会在更新结束后恢复。
Agent 管理页的「版本管理 / 回退」列出已安装版本，更新后保留旧版本，可切换、回退或手动删除旧版本；当前使用的版本禁止删除。
设置 → 关于 Ai-XS 展示项目、反馈与发布记录，并从 [GitHub Releases](https://github.com/LXiangSong/Ai-XS/releases) 检查和下载应用更新。安装前核对包名、签名与递增的版本号，随后交由 Android 系统安装。
GitHub 发布 APK 的要求见 [更新发布说明](android/UPDATES.md)。
界面使用统一的色板与字阶、分段式首页（顶栏 / 对话流 / 输入卡）、
自定义抽屉与全高会话面板，对话采用右侧用户气泡、Markdown 正文、代码卡片与可折叠工具行。
构建使用 `assembleRelease`、`lintRelease` 与 APK 签名校验。
跨 Agent 会话、上下文交接和权限确认通过私有进程协议集成测试；第三方 API 与模型的具体能力以实际接口为准。

### 运行进度与诊断

安装、初始化、环境检查、Agent 更新和版本切换在操作页面显示进度与结果，不会自动打开运行日志。失败后可点击“查看运行日志”，直接筛选这次操作的 Debug 记录。应用更新的检查与下载结果也保留在“关于”页面。

设置 → 运行日志：普通日志记录阶段、结果与提醒；Debug 记录子进程输出、退出码、耗时、Agent 通信诊断、浏览器错误与应用异常堆栈。可按级别、来源、操作编号与关键词筛选，暂停刷新、复制当前内容，或导出包含设备/版本信息与轮转日志的脱敏诊断报告。旧版本的输出保留在“历史日志”。查看历史时不会自动滚动，点击“跳到最新”才恢复跟随。

Agent 的 `app_logs` 工具支持 `runtime`（普通）、`debug`、`browser`、`legacy` 与 `logcat`（Ai-XS 自身进程）来源；普通和 Debug 日志还可通过 `level`、`origin`、`operation`、`query` 精确筛选。普通日志和 Debug 分别保留当前文件与上一份轮转文件，每份上限约 512 KB / 1 MB。

### Agent 管理

设置 → Agent 管理集中管理安装、更新、版本切换与启停。可全部启动/停止，或分别启动/停止 Codex、Claude Code、DeepSeek。停止前提示中断任务，状态跨服务重启保留；启动验证本地程序后置为空闲就绪，实际任务仍按需启动进程，避免三个 Agent 同时占用内存。启停进度与结果保留在当前页，失败时可打开对应操作日志。
