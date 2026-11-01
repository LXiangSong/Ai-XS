# 开发指南

## 环境与版本

Python 3.12+、JDK 21、Android SDK 35 和 Build Tools 35.0.0。Gradle 使用仓库 Wrapper，校验下载哈希；不依赖全局 Gradle。Termux 构建另外需要原生 `aapt2`，脚本会自动选择并关闭不适用的 Gradle 原生功能。

`android/version.properties` 是 App 和打包后端的唯一版本来源。修改版本时同时递增 `versionCode` 和三段 `versionName`；后端 ZIP 中的 `version.json` 由资产脚本生成。

普通源码构建使用仓库已固定的 Ubuntu／PRoot 资产，不必运行 `prepare_runtime.py`。该脚本仅用于有意更新基础资产，需要 Ubuntu 原归档和 Termux 原生库，更新后必须复核 `manifest.json` 和第三方说明。

## 本地签名

签名保存在 `.state/android-signing/`，不进入 Git。仅开发测试可以生成自己的身份：

```sh
mkdir -p .state/android-signing
# 交互式输入本机测试密码，避免把真实发布密码写入命令历史。
keytool -genkeypair -keystore .state/android-signing/release.p12 \
  -storetype PKCS12 -alias aixs -keyalg RSA -keysize 2048 \
  -validity 3650 -dname 'CN=Ai-XS Local Development'
```

把同一密码单独保存到 `.state/android-signing/password`，并限制本机文件权限。现有正式安装的覆盖升级必须使用原正式证书；开发者自己的证书无法覆盖它。GitHub CI 自动生成短期测试证书，不上传签名材料或测试 APK。

## 检查与构建

在仓库根目录执行：

```sh
python android/tools/check_project.py
python android/tools/test_all.py
```

全量入口依次检查源码可移植性、Markdown 本地链接、运行时哈希，运行所有 Python 集成测试、Java 单元测试、Release Lint、签名构建和 APK 来源一致性检查。第一次需要联网，缓存齐备后使用 `--offline`。

只检查某一层：

```sh
python -m unittest discover -s android/tests
cd android
./gradlew :app:testReleaseUnitTest :app:lintRelease
```

只构建 APK：

```sh
python android/tools/build.py --lint
python android/tools/verify_release.py                # 检查本机签名与源码
python android/tools/verify_release.py --production   # 额外要求正式证书
```

输出为 `dist/Ai-XS-<version>.apk`、`.apk.sha256`、`.verification.json`；全量测试另输出 `.validation.json`。核验报告不包含私有目录、用户名、签名密码或 API 配置。

协议测试使用模拟 CLI 和本机临时状态；测试通过不能替代真实设备上的字体／键盘／生命周期回归或真实模型兼容性测试。新增涉及状态持久化、并发、重启、输入保留的行为应补相应回归。

## 修改约定

- 将 Android 纯状态合并逻辑放在可运行 JVM 测试的类中，避免只凭界面源码字符串断言验证行为。
- 队列和即时发送共享请求编号；结果未知时先核对，不自动重复执行。
- 实时事件按执行尝试与序号隔离；局部刷新不能覆盖较新的流式内容或历史分页。
- 新增后端模块时更新 `prepare_native_assets.py` 的 `MODULES`，新增私有文件类型时同步排除规则。
- 提交前检查 `git diff --check` 和 `git status`；源码、资源禁止绝对软链接。`.state/`、截图证据、用户数据与签名不提交。

发布步骤和上版兼容要求见[发布流程](../android/UPDATES.md)。
