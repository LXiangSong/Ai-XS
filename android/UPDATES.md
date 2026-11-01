# Ai-XS 更新发布

项目仓库固定为 https://github.com/LXiangSong/Ai-XS。

## App 更新

用户在「设置 → 关于 Ai-XS → 检查 GitHub 更新」手动检查，无需初始化 Linux 环境。
「包含测试版更新」决定是否检查 prerelease；测试版 App 默认包含测试版，正式版默认只检查正式版。
没有 Release、Release 没有 APK、网络错误和已是最新版会分别提示。

发布步骤：

1. 在 `android/version.properties` 同时递增 `versionCode` 和 `versionName`；App 和后端共用这一份版本。版本名使用三段版本号，如 `0.9.11-alpha` 或 `1.0.0`。
2. 使用原来的正式签名证书构建。保管 `.state/android-signing`，不要提交到 GitHub；更换签名将无法覆盖现有安装。
3. 执行 `python android/tools/test_all.py`（缓存齐备可加 `--offline`），得到 `dist/Ai-XS-<version>.apk`、`.apk.sha256`、`.verification.json`、`.validation.json`。再执行 `python android/tools/verify_release.py --production`，确认使用现有正式证书。不能将临时 CI 证书的 APK 用于发布。
4. 将完整源码提交推送后，等待对应提交的 GitHub Actions 成功；给该提交创建 `v<version>` 标签，创建 Release 并上传 APK、SHA-256 和两份核验报告。测试版勾选 pre-release。
5. 上传完成后用户即可检查更新。仅提交源代码不会产生可安装的应用更新。

更新器比较语义版本，忽略草稿和无效标签；APK 名称需以 `Ai-XS` 开头，扩展名 `.apk`。
多架构发布时优先选择名称包含 `arm64` 的包，其次 `universal`，排除 x86、armv7、debug 包。
只从本仓库 Releases 下载，跟随 GitHub 的 HTTPS 资源重定向。
核对下载大小、GitHub 提供的 SHA-256 digest（如有）、包名、版本代码、Android 最低版本和签名。
下载在独立前台服务中进行，不需要停止 Agent；安装由系统完成，保留 App 私有环境与用户数据。
首次安装更新可能需要用户允许「安装未知应用」。

如果仓库尚未发布可用 Release，显示「GitHub 暂无可用发布版本」，不会误报「已是最新版」。

## Agent 更新

「设置 → Agent 管理 → 检查全部 Agent 更新」或单个 Agent 的「版本与安装 → 检查官方更新」读取各自官方 npm 包的 `latest` 标签：

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

设置 → Agent 管理 → 版本与安装 → 版本管理 / 回退。
新版本在独立 release 目录安装，旧 release 永久保留至手动删除。
版本列表标记当前使用的版本与安装时间；同版本重复安装按独立目录分别管理。
切换 / 回退先运行目标启动程序核对版本，验证通过后切换 `current` 和启动链接，再恢复服务。
目标损坏或检查失败时保持当前版本。删除仅移除指定旧 release 目录，禁止删除当前指针或安装元数据指向的版本。
有任务运行时拒绝版本变更。已安装的旧版 App release 目录可直接识别，不需要重新安装。

### Agent 启停

设置 → Agent 管理提供全部启动、全部停止和每个 Agent 的独立启停按钮。停止前需确认任务中断；单个停止不会中断其他 Agent。全部停止会关闭共享服务，单个停止仅关闭对应 Agent。停止状态写入私有状态目录，服务重启后保留，直到手动启动。启动验证已安装 CLI 后置为可用，空闲时按需释放进程，三个 Agent 共用运行环境。

## 发布前核对

执行 `python android/tools/test_all.py --offline`（首次下载依赖时去掉 `--offline`），验证 Python 协议测试、Java 测试、完整 Lint、Release 构建与签名。再完成目标 APK 的真机回归，核对安装版本、升级数据保留和界面操作。保留上版签名，上传的 APK 与校验文件必须来自本次构建。

源码推送后等待对应提交的 GitHub Actions 通过，再创建可安装的 Release。CI 使用临时测试签名，其构建包不能作为覆盖更新发布；发布 APK 使用项目既有正式签名。发布后重新下载资产核对 SHA-256，确认 Release 标签、源码提交、APK versionName / versionCode 一致。

可使用下列命令发布已经核验的提交；`<commit>` 必须是 CI 成功的完整提交编号，说明从文件读取，避免丢失换行：

```sh
git tag -a v<version> <commit> -m 'Ai-XS <version>'
git push origin v<version>
gh release create v<version> --repo LXiangSong/Ai-XS --verify-tag --prerelease \
  --title 'Ai-XS <version>' --notes-file release-notes.md \
  dist/Ai-XS-<version>.apk dist/Ai-XS-<version>.apk.sha256 \
  dist/Ai-XS-<version>.verification.json dist/Ai-XS-<version>.validation.json
```

正式版去掉 `--prerelease`。发布说明需覆盖上次公开版本以来的变化；本机中间版本尚未公开时，不能只列最后一次修改。

更新说明区分已实现功能与设计稿；协作模式分别记录模拟 CLI 测试、生产调度器与真实 CLI 的本地模拟 API 核验、真机 UI 和真实第三方 API 测试，不能混淆验证范围。仓库与源代码包不包含开发机器的 `.state`、用户会话、API 密钥或签名身份。
