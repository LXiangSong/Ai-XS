# 第三方组件

Ai-XS 包含或在用户初始化后安装下列第三方组件。各组件保留其原始版权和许可，Ai-XS 不改变这些条款。

| 组件 | 来源及构建信息 |
| --- | --- |
| Ubuntu Base ARM64 | [Ubuntu Base 24.04.5](https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/)，发行版内各软件的版权说明位于 `/usr/share/doc/*/copyright`；源包见 [Ubuntu 源包仓库](https://archive.ubuntu.com/ubuntu/pool/) |
| PRoot 5.1.107.101 | [Termux PRoot 源码及许可](https://github.com/termux/proot/tree/v5.1.107.101)，GPL-2.0；[Termux 构建配方及补丁](https://github.com/termux/termux-packages/tree/master/packages/proot) |
| talloc 2.5.0 | [talloc 源码、许可和发布包](https://talloc.samba.org/)，[Termux 构建配方](https://github.com/termux/termux-packages/tree/master/packages/libtalloc) |
| libandroid-shmem 0.7 | [Termux 源码及 BSD 3-Clause 许可](https://github.com/termux/libandroid-shmem/tree/v0.7) |
| Material Components for Android 1.14.0 | [Google Material Components](https://github.com/material-components/material-components-android)，Apache-2.0 |
| AndroidX AppCompat / Core / ConstraintLayout | [AndroidX](https://android.googlesource.com/platform/frameworks/support/)，Apache-2.0 |
| Markwon 4.6.2 | [Markwon](https://github.com/noties/Markwon)，Apache-2.0 |
| Gradle Wrapper 8.11.1 | [Gradle](https://github.com/gradle/gradle)，Apache-2.0 |

内置 Ubuntu 归档和 ARM64 库的原文件、打包文件 SHA-256 及来源记录在 `android/app/src/main/assets/runtime/manifest.json`。
`android/tools/prepare_runtime.py` 记录内置资产的转换过程：Ubuntu 硬链接转普通文件；PRoot 的动态库名 `libtalloc.so.2` 等长替换为 APK 可加载的 `libtalloc.so`；其余库不修改。

Node.js、Codex、Claude Code 和 DeepSeek 由用户在初始化或 Agent 安装页面下载各自发布的软件包，默认经由国内镜像（npmmirror、清华）分发；Node.js 保留固定的官方 SHA-256 校验，Claude 原生包验证发布元数据中的 SHA-512。Ubuntu 依赖使用中科大／清华 ARM64 镜像并保留 Ubuntu 索引签名校验。不把个人账户、密钥或已安装 Agent 程序嵌入发布 APK。
其使用遵循各自软件许可和服务条款，安装来源和确切版本由 App 显示。
