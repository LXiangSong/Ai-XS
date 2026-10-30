# 0.8.8-alpha 发布核验

版本：`0.8.8-alpha`，`versionCode 41`，ARM64，最低 Android 8.0。

## 自动检查

执行 `python android/tools/test_all.py --offline`：

- 75 项 Python 协议、会话、权限、配置、扩展、版本及生命周期测试通过。
- 31 项 Java 逻辑测试通过，包括操作结果恢复、日志筛选、消息、提问与版本比较。
- Release 构建及 APK 签名检查通过；Lint 0 个错误、56 个警告。
- Python 3.14 测试期间有 SQLite 连接清理的 ResourceWarning，未导致测试失败。

## 真机回归

在 OnePlus PYB110 上覆盖升级，保留原签名及应用数据，安装包与本地构建 SHA-256 一致。

- 原有会话、8 轮 / 27 步统计及 223.2k Token 汇总保留。
- 全部 Agent 停止 / 启动、单独 Codex 停止 / 启动通过，最终恢复全部可用。
- Agent 服务运行时可完成只读环境检查；进度及结果在当前页面显示。
- 完成和失败结果在返回后仍可查看，支持手动收起；失败链接进入该操作的 Debug 日志。
- 普通 / 调试 / 历史日志切换及错误筛选可用。
- API 页切换 DeepSeek 后旋转屏幕，Activity 重建仍保留所选 Agent；未修改 API 配置。
- 扩展库、GitHub 导入对话框、设置返回层级、关于版本与 GitHub 更新检查通过。

## 范围

这是 alpha 版本。本次真机回归覆盖上述操作，没有逐项重跑全部第三方模型的付费请求，也不代表所有 Android 设备已兼容。协议自动测试使用模拟 CLI / API。

当前 APK 支持三个 Agent 顺序接手同一会话。并行协作、多模型参与者调度及中途改派目前是设计与独立可行性验证，尚未接入 APK，见 [协作方案](../design/COLLABORATION.md)。

发布流程和资产核对方法见 [更新发布说明](UPDATES.md)。GitHub Actions 使用临时测试签名，发布 APK 复用项目既有签名。
