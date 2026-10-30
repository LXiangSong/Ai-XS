# Ai-XS 更新发布

项目仓库固定为 https://github.com/LXiangSong/Ai-XS。

## App 更新

用户在「设置 → 关于 Ai-XS → 检查 GitHub 更新」手动检查，无需初始化 Linux 环境。
「包含测试版更新」决定是否检查 prerelease；测试版 App 默认包含测试版，正式版默认只检查正式版。
没有 Release、Release 没有 APK、网络错误和已是最新版会分别提示。

发布步骤：

1. 在 `android/app/build.gradle` 同时递增 `versionCode` 和 `versionName`；版本名使用三段版本号，如 `0.8.6-alpha` 或 `1.0.0`。
2. 使用原来的正式签名证书构建。保管 `.state/android-signing`，不要提交到 GitHub；更换签名将无法覆盖现有安装。
3. 执行 `python android/tools/build.py --lint`，得到 `dist/Ai-XS-<version>.apk` 和 `.apk.sha256`。
4. 在项目 GitHub Releases 创建 `v<version>` 标签的发布，填写更新说明，上传 APK 与 SHA-256 文件。测试版勾选 pre-release。
5. 上传完成后用户即可检查更新。仅提交源代码不会产生可安装的应用更新。

更新器比较语义版本，忽略草稿和无效标签；APK 名称需以 `Ai-XS` 开头，扩展名 `.apk`。
多架构发布时优先选择名称包含 `arm64` 的包，其次 `universal`，排除 x86、armv7、debug 包。
只从本仓库 Releases 下载，跟随 GitHub 的 HTTPS 资源重定向。
核对下载大小、GitHub 提供的 SHA-256 digest（如有）、包名、版本代码、Android 最低版本和签名。
下载在独立前台服务中进行，不需要停止 Agent；安装由系统完成，保留 App 私有环境与用户数据。
首次安装更新可能需要用户允许「安装未知应用」。

当前仓库尚未发布 Release 时，显示「GitHub 暂无可用发布版本」，不会误报「已是最新版」。

## Agent 更新

「设置 → Agent 更新」或「Agent 管理 → 安装/版本 → 检查官方更新」读取各自官方 npm 包的 `latest` 标签：

| Agent | 版本来源 |
| --- | --- |
| Codex | `@openai/codex` |
| Claude Code | `@anthropic-ai/claude-code-linux-arm64` |
| DeepSeek | `@deepseek-ai/dsh` |

更新安装检查时选定的确切版本，不随安装期间的 latest 变化；不会自动降级。
有任务运行时拒绝更新。空闲时暂时关闭 Agent 服务，以独立目录下载和验证新版本，成功后切换启动链接，并恢复原先启动的服务。
安装失败时现有版本继续保留；项目、会话、第三方 API 和扩展配置不在 Agent 安装目录中，不会被更新覆盖。
GitHub 和离线部署包导入仍可用于升级，包格式与原安装功能一致。

## Agent 版本管理与回退

设置 → Agent 管理 → 安装/版本 → 版本管理 / 回退。
新版本在独立 release 目录安装，旧 release 永久保留至手动删除。
版本列表标记当前使用的版本与安装时间；同版本重复安装按独立目录分别管理。
切换 / 回退先运行目标启动程序核对版本，验证通过后切换 `current` 和启动链接，再恢复服务。
目标损坏或检查失败时保持当前版本。删除仅移除指定旧 release 目录，禁止删除当前指针或安装元数据指向的版本。
有任务运行时拒绝版本变更。已安装的旧版 App release 目录可直接识别，不需要重新安装。

### Agent 启停

设置 → Agent 管理提供全部启动、全部停止和每个 Agent 的独立启停按钮。停止前需确认任务中断；单个停止不会中断其他 Agent。全部停止会关闭共享服务，单个停止仅关闭对应 Agent。停止状态写入私有状态目录，服务重启后保留，直到手动启动。启动验证已安装 CLI 后置为可用，空闲时按需释放进程，三个 Agent 共用运行环境。
