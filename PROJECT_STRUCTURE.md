# 项目结构

Ai-XS 是独立原生 Android App，不依赖 AI-Web 服务或网页前端。

| 目录 / 模块 | 职责 |
| --- | --- |
| `android/app/src/main/java/app/aixs/mobile` | 原生 Material 界面、手机工具、Linux 进程管理、App / Agent 更新 |
| `NativeHomeActivity` / `ConversationRenderer` | 会话、输入、流式事件和消息显示 |
| `CollaborationActivity` | 协作列表、计划、参与者、任务进度 / 改派 / 验收及资源设置 |
| `RuntimeActivity` / `AboutActivity` | 设置、环境初始化、Agent 管理（安装、启停与更新）、关于与应用更新 |
| `NativeToolsActivity` / `ExtensionsActivity` | API、MCP 和共享扩展库 |
| `NativeEngine` | Android 与 Linux 后端之间的私有 stdin/stdout 通信 |
| `RuntimeService` / `RuntimeManager` | 共享 Linux 环境、Agent 部署与服务生命周期 |
| `AppUpdateService` / `AppUpdater` | GitHub APK 下载、校验和系统安装交接 |
| `DeviceMcpServer` / `PhoneAccessibilityService` / `BrowserTools` | 手机操作、应用日志及开发浏览器工具 |
| `Ui` / `NativeUi` | 统一颜色、排版、卡片、按钮和页面布局 |
| `android/runtime/native_host.py` | 打包后端入口、请求分发与更新维护保护 |
| `agent_core.py` / `rpc_process.py` / `engine_manager.py` | 三个 CLI 的进程与协议协调 |
| `claude_bridge.py` / `deepseek_bridge.py` | Claude stream-json、DeepSeek ACP 协议适配 |
| `shared_conversations.py` | 独立会话、跨 Agent 历史交接、编辑分支与统计 |
| `collaboration_service.py` / `collaboration_store.py` | 工作流状态、SQLite 持久化、计划、改派、验收与原会话交接 |
| `worker_pool.py` / `collaboration_worker.py` / `execution_profiles.py` | 私有进程、独立执行配置、实例权限和已确认退出回收 |
| `task_scheduler.py` / `collaboration_workspace.py` / `collaboration_mcp.py` | 执行名额、资源保护、项目隔离与内置工具资源约束 |
| `provider_manager.py` / `permission_modes.py` | API 配置、模型配置与权限选择 |
| `extension_manager.py` / `mcp_manager.py` / `assistant_tools.py` | Skills / 插件获取、分配、兼容性和 MCP 配置 |
| `android/runtime/agent_package.py` | 独立目录安装、包校验、版本检查与原子切换 |
| `android/tools` | 运行时资产准备、Gradle 构建、部署包制作与全量测试 |
| `android/tests` / `android/app/src/test` / `tests/fake_*` | Python 协议集成与 Java 界面逻辑测试 |
| `design/COLLABORATION.md` / `design/collaboration-preview.html` | 协作实现规格与独立交互原型，含中途改派、配置、恢复和验收；完整规格包含后续高级功能，首版范围见 `android/COLLABORATION.md` |
| `design/COLLABORATION_FEASIBILITY.md` / `design/collaboration-multi-model-verification.json` | 真实 CLI 与本地模拟 API 的隔离 / 六参与者组合核验证据，不等同 APK 验收 |

## 数据边界

三个 Agent 共用一份 ARM64 Linux、Node、Python 和 Git。项目在 `/root/projects`，Agent 程序在 `/opt/agents`，共享配置、历史和扩展在 `/opt/aixs/native/.state`。
协作数据库位于 `.state/collaboration/collaboration.sqlite`，每次执行的配置、日志和工作区位于其 `attempts/<id>`，密钥不进入工作流展示记录。
这些均位于 Android App 私有目录，不是开发仓库的可发布内容。
App 升级保留私有目录；Agent 更新仅切换程序版本，不覆盖项目、密钥、会话和扩展。

开发机器的 `.state`、`.uploads`、签名证书、API 密钥、构建缓存和测试证据不得提交。
运行时后端 ZIP、安装脚本和版本目录由 `prepare_app_assets.py` 从受控源文件生成。
内置基础系统与 PRoot 库附有 `assets/runtime/manifest.json` 的来源和哈希记录。

## 开发入口

- 构建：`python android/tools/build.py --lint`
- 全量测试：`python android/tools/test_all.py --offline`（首次下载依赖时去掉 `--offline`）
- 发布：[android/UPDATES.md](android/UPDATES.md)
- 使用与初始化：[android/INSTALLATION.md](android/INSTALLATION.md)
