# 0.9.11-alpha 发布核验

版本：`0.9.11-alpha`，`versionCode 59`，包名 `app.aixs.mobile`，ARM64，最低 Android 8.0。App 与打包后端的版本均来自 `android/version.properties`。

## 本轮调整

- 保留独立／协作共用会话、队列、引入、停止与继续、国内初始化镜像及环境诊断等既有行为。
- 将 6 个开发手机临时目录的源码／资源软链接恢复为普通文件；清理了文档中旧版本、已移除原型和过时能力说明。
- 增加统一文档索引、开发指南、项目可移植性检查与 APK 一致性核验，构建与 CI 共用同一检查入口。
- 发布包含 0.9.0 至 0.9.10 的本地累积修改：协作流式显示、工作流管理、消息队列、草稿保护、发送去重、任务接力与页面闪烁修复。详见[版本记录](../CHANGELOG.md)。

## 自动检查

本地执行 `python android/tools/test_all.py --offline`，检查源码与文档链接、固定运行时资产哈希、全部 Python 集成测试、Java 状态逻辑测试、Release Lint、签名构建和 APK 打包一致性。

测试覆盖三引擎会话／协议、审批与提问、会话编辑／删除、用量、扩展、Agent 管理、协作实例隔离、资源／预算、成果与恢复、排队／引入／去重、停止／继续、历史分页合并、草稿附件保留、缓存失效重载和构建源文件可移植性。

每次全量运行生成 `dist/Ai-XS-0.9.11-alpha.validation.json`，记录实际测试数量、失败数与 Lint 结果；`verify_release.py --production` 生成 `.verification.json`，记录 APK 版本、大小、SHA-256、签名与打包源码一致性。两份报告随本次 Release 上传，不把 CI 的临时证书包用于发布。

## 发布一致性

正式证书 SHA-256：`0ed3ff23c0a576a8030df21f247ed8de9e89f06216d2732a3d45a8439bcb0c26`。

`verify_release.py` 核对包名、versionName／versionCode、最低 Android 版本、ARM64、APK 签名、校验文件、后端模块与版本 JSON、基础系统、安装脚本和原生库。打包源码须与发布提交逐文件一致；GitHub CI 通过后才发布标签和安装包，发布后重新下载核对哈希。

仓库不包含私有 `.state`、用户会话、API 密钥、签名材料、开发截图或构建缓存。Git 历史沿用原仓库，未重写已有提交和旧标签。

## 设备与实际接口边界

本轮没有进行 Android 真机点击、长期后台压力或付费模型接口复验。当前可访问的 ADB 目标不是 Android 设备，不能替代真机验证；不把历史手机测试结果写成本版实测。协议自动测试使用隔离的模拟 CLI／接口，不证明每个模型、网关、手机或外部 MCP 都兼容。

历史证据分别保留于 [0.9.0 核验](RELEASE_VERIFICATION_0.9.0.md)、[同会话模式本地验证](CONVERSATION_MODES_VERIFICATION.md)和[Agent 协议核查](AGENT_PROTOCOL_AUDIT.md)。它们描述各自版本的验证范围。

仍需持续关注：不同 Android 厂商的后台策略、复杂项目合并、真实网关限流与 CLI 更新兼容性。工作区隔离不限制全部 shell 行为；没有自动最优分工或精确金额预算。
