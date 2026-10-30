# Ai-XS 安装与初始化

Ai-XS 是独立原生 Android App，与 AI-Web 分开部署。测试版从 `android/app/build/outputs/apk/release/app-release.apk` 构建，保留本地签名身份覆盖安装。

首次打开进入原生会话页。尚未初始化时会提示“去设置 / 稍后”；只有进入 **设置 → 运行环境** 并主动点击“初始化环境”，才开始下载和准备一套共享 ARM64 Linux 环境。完成后在 **设置 → Agent 管理** 按需安装 Codex、Claude Code、DeepSeek，再于 **设置 → API 服务配置** 为每个 Agent 配置官方 API 或第三方 API。

新配置不继承现有 Termux / AI-Web 的账户信息。服务端配置保存在 Ai-XS 私有目录；cooper-api 等第三方服务可在 Agent 对应页面填写 Base URL、协议、模型 ID 和密钥。

## Material 主题与页面

首页使用 Material 3 会话工作区：顶部 Agent 切换与会话操作、左侧抽屉菜单、中间消息区；小号项目入口位于任务输入框上方，模型选择位于思考强度左侧，附件使用回形针图标。设置、API 表单、审批、列表及开发浏览器使用 Material 组件和深浅主题。

## 验收状态

Gradle Wrapper、AGP、Google Maven Material 依赖、SDK Build Tools 与 APK Release 构建已在开发环境验证。手机上完成覆盖安装及新版检查后，应进一步验收不同字号、IME 键盘、卡片滚动、深色模式与屏幕阅读器。

接口和工具发现检查不发送推理请求。真正的 API 推理、审批组合和所有 Agent 场景还需要分别验收。请勿将 alpha 测试包描述为正式版或称所有原网页功能均已迁移。
