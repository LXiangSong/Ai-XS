# 项目结构

## 主路径

```text
Android Material 页面
    → NativeEngine（私有 stdin/stdout）
    → android/runtime/native_host.py
    → 会话／执行控制／待发队列／协作调度
    → Codex app-server／Claude stream-json／DeepSeek ACP
```

三个 Agent 共用一套运行环境；独立会话保留原生绑定，协作执行使用独立配置和工作区。界面消费归一后的真实事件，协作输出按执行尝试隔离。

| 目录或模块 | 职责 |
| --- | --- |
| `android/app/src/main/java/app/aixs/mobile/` | 原生页面、进程生命周期、手机工具与更新器 |
| `NativeHomeActivity`、`ConversationRenderer` | 会话输入、消息流与 Markdown 展示 |
| `ConversationQueue`、`ExecutionButton`、`ConversationSnapshots` | 待发操作、单按钮状态、缓存与增量快照合并 |
| `ConversationCollaboration`、`CollaborationEvents` | 成员／任务面板与协作流式序号合并 |
| `SessionInsight`、`SessionInsightPanel` | 文件、子代理及工具来源详情 |
| `RuntimeManager`、`RuntimeService`、`RuntimeCommand` | 共享环境、前台服务、命令超时与清理 |
| `RuntimePackages`、`RuntimeSources`、`RuntimeChecks` | 国内镜像、依赖补装与分项诊断 |
| `NativeEngine` | Android 与后端的私有管道，不提供 HTTP 工作台 |
| `AppUpdater`、`AppUpdateService` | GitHub APK 获取、版本／签名校验与安装交接 |
| `DeviceMcpServer`、`PhoneAccessibilityService`、`BrowserTools` | 手机无障碍、日志与开发浏览器工具 |
| `android/runtime/native_host.py` | 后端请求分发、控制请求独立执行队列与维护保护 |
| `agent_core.py`、`rpc_process.py`、`engine_manager.py` | Agent 进程、协议路由与启停 |
| `claude_bridge.py`、`deepseek_bridge.py`、`dsh_live_events.mjs` | Claude／DeepSeek 协议适配与实时事件 |
| `shared_conversations.py`、`conversation_usage.py` | 会话、历史交接、编辑分支与用量 |
| `conversation_queue.py`、`conversation_execution.py` | 持久队列、停止／继续、引入与派发 |
| `collaboration_service.py`、`collaboration_store.py`、`collaboration_session.py` | 工作流、SQLite、原会话投影与发送回执 |
| `collaboration_api.py`、`collaboration_policy.py`、`collaboration_transcript.py` | 工作流接口、预算／验证／退避与历史分页 |
| `worker_pool.py`、`collaboration_worker.py`、`execution_profiles.py` | 独立执行实例、冻结配置与退出核对 |
| `task_scheduler.py`、`collaboration_workspace.py`、`collaboration_mcp.py` | 资源配额、项目隔离、依赖成果与内置工具资源 |
| `native_children.py`、`realtime_events.py`、`file_reports.py` | 子代理归一、流式事件、文件变化报告 |
| `provider_manager.py`、`permission_modes.py`、`mcp_manager.py`、`extension_manager.py` | API、模型、权限、MCP 与扩展库 |
| `android/runtime/agent_package.py` | Agent 版本安装、校验与原子切换 |
| `android/version.properties` | App 与打包后端共用的版本名和版本代码 |
| `android/tools/` | 资产打包、项目检查、Gradle 构建与发布核验 |
| `android/tests/`、`android/app/src/test/`、`tests/fake_*` | Python 集成测试、Java 状态逻辑测试与模拟 CLI |
| `docs/`、`android/*.md`、`design/` | 文档索引、使用／接口说明、架构设计与历史证据 |

后端暂保留平铺模块结构，便于在 Android 私有 Linux 环境中按明确清单打包；`prepare_native_assets.py` 是后端资产清单入口，不扫描用户目录。

## 数据与构建边界

App 内项目位于 `/root/projects`，Agent 程序位于 `/opt/agents`，状态在 `/opt/aixs/native/.state`。工作流数据库是 `.state/collaboration/collaboration.sqlite`，执行成果与配置位于 `attempts/<id>`。这些路径均在 App 私有环境，不是开发仓库文件。

开发目录中的 `.state/`、`.uploads/`、`dist/`、签名、密钥、截图证据、Gradle 缓存和生成的后端 ZIP 均不提交。受控的 Ubuntu／PRoot 基础资产保留在源码中，并以 `assets/runtime/manifest.json` 固定来源与哈希。

源码必须是普通文件，不能依赖开发手机的绝对软链接。`check_project.py` 检查可移植性、文档链接和基础资产哈希；`verify_release.py` 核对 APK 中的后端、基础资产、版本与签名。

## 开发入口

- [文档索引](docs/README.md)
- [开发与检查](docs/DEVELOPMENT.md)
- [发布流程](android/UPDATES.md)
- [安装与初始化](android/INSTALLATION.md)
- [协作功能与边界](android/COLLABORATION.md)
