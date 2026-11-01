# Ai-XS

Ai-XS 是 Android 原生 Agent 工作空间，在同一条会话中使用 Codex、Claude Code 和 DeepSeek Harness，管理项目、工具、API 与协作任务。界面使用 Material 3，通过私有进程管道连接 Agent，不依赖 AI-Web 或网页工作台。

当前版本：**0.9.11-alpha**，Android 8.0 及以上，ARM64。仍处于测试阶段，验证范围见[发布核验](android/RELEASE_VERIFICATION.md)。

[下载安装](https://github.com/LXiangSong/Ai-XS/releases) · [安装与初始化](android/INSTALLATION.md) · [版本记录](CHANGELOG.md) · [文档索引](docs/README.md)

## 安装和开始使用

1. 从本仓库 Releases 下载 `Ai-XS-<version>.apk`。升级保留 App 私有环境、项目、API 配置和历史，覆盖安装必须使用同一签名。
2. 打开 **设置 → 运行环境 → 初始化环境**。初始化仅由用户在这里启动，不在首页自动执行。
3. 在 **设置 → Agent 管理** 按需安装 Agent，在 **设置 → API 服务配置** 填写对应 API、协议、模型和密钥。
4. 选择项目并发送任务。切换协作模式前配置至少两位成员，可使用相同 Agent 的不同模型。

三个 Agent 共用一套私有 ARM64 Linux、Node、Python 和 Git。APK 内置基础系统和 PRoot；Python、Git、TLS、Node 与 Agent 在初始化或安装时下载，Ubuntu 依赖使用中科大／清华镜像，Node／npm 使用国内镜像。新安装不读取其他 App 或 Termux 的账户。

## 会话与执行

- 独立与协作模式共用会话、项目、输入框、附件、历史和用量。切换 Agent 会交接历史，已执行操作不回滚。
- 正文、可用的思考摘要、命令和工具过程按实际协议流式显示；服务端没有提供的内容不模拟生成。
- 输入有文字、引用或附件时显示发送；运行中发送进入待发队列。队列支持编辑、删除及显式引入当前任务。
- 运行中输入为空时显示停止，停止会中断当前执行。没有可自动接力的普通待发项时，空输入可继续原任务；队列明确暂停或队首失败／待核对时不会擅自派发。
- 任务完成及队列接力刷新保留当前页面、协作流式内容与已加载历史，不因缓存失效返回新会话页。详见[执行按钮与队列](android/MESSAGE_QUEUE.md)。

会话详情汇总文件改动、子代理和工具来源；用户消息支持编辑分支，回复支持复制和引用。用量按实际执行来源保存，未上报的上下文或计费数据标为未知。

## 协作、环境与工具

协作成员可分别选择 Agent、模型、API 来源、职责、思考强度和权限。已提供并行／开发／研究模板、审核依赖、结构化交接、有限返工、执行预算、资源调度、恢复核对、成果预览与人工合入。Git 项目使用 worktree，非 Git 项目使用有界快照；工作区隔离不限制任意 shell 或外部 MCP 行为。

Agent 管理支持安装、更新、启停和版本回退；运行中拒绝更新程序。Skills／插件库支持 GitHub、本地 ZIP 或 SKILL.md，并显示兼容性；MCP、手机无障碍与开发浏览器工具按配置使用。环境检查／修复按阶段记录镜像、命令、退出码与最后输出，日志可筛选并导出脱敏诊断。

[协作使用与边界](android/COLLABORATION.md) · [运行环境与构建](android/README.md) · [第三方组件](THIRD_PARTY_NOTICES.md)

## 开发与验证

需要 Python 3.12+、JDK 21、Android SDK 35／Build Tools 35.0.0。仓库包含固定的 ARM64 基础系统与原生库，普通构建无需重新生成它们。Gradle Wrapper、AGP 和依赖版本固定在工程中。

```sh
python android/tools/check_project.py
python android/tools/test_all.py          # Python、Java、Lint、签名 Release 构建
python android/tools/verify_release.py --production
```

首次构建需联网下载 Gradle／Maven 依赖，缓存完整后可加 `--offline`。本地构建需自行准备签名，步骤见[开发指南](docs/DEVELOPMENT.md)；GitHub CI 使用临时测试证书，其 APK 不用于覆盖发布。

版本统一维护在 `android/version.properties`。APK、SHA-256 和核验报告输出到 `dist/`，开发日志、签名材料和生成资产不提交。

[项目结构](PROJECT_STRUCTURE.md) · [开发指南](docs/DEVELOPMENT.md) · [发布流程](android/UPDATES.md)
