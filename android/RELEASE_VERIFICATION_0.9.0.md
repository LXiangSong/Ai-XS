# 0.9.0-alpha 发布核验

版本：`0.9.0-alpha`，`versionCode 42`，ARM64，最低 Android 8.0。协作首版范围见 [协作说明](COLLABORATION.md)。

## 自动检查

执行 `python android/tools/test_all.py --offline`：

- 91 项 Python 协议、会话、配置、扩展、版本、生命周期及协作测试通过，其中新增 16 项协作测试。
- 31 项 Java 逻辑测试通过。
- Release 构建、Lint 和 APK 签名检查通过；Lint 0 个错误、60 个警告（新增 4 项是界面字符串国际化建议）。
- Python 3.14 的既有 SQLite 连接清理 ResourceWarning 仍可出现，未导致测试失败。

协作测试包含：同 Agent 不同模型重叠运行、第三任务排队、全局 / 重任务 / 手机 / 同项目限制、API 网关并发上限、资源信息过期、三 Agent 依赖执行、人工验收、幂等创建、非法计划保留草稿、双实例权限编号隔离、普通会话排队取消、运行中改派、重启不重放、配置快照不随全局设置变化、注册失败不遗留 worker、未预留的内置工具不发送调用、Agent 提交代码仍进入交付补丁。

这些自动协议测试使用模拟 CLI，不代表模型推理质量或全部外部 API 已验证。

## 已安装 CLI 与生产调度器

另行启动本轮 `CollaborationService`、`WorkerPool` 和 `collaboration_worker.py`，使用已安装的真实 Codex、Claude Code、DeepSeek CLI，对本机 loopback 模拟 API 请求。

三个 Agent 分别通过：两个不同模型实例同时请求、不同 Home 和工作区、停止实例 A 后实例 B 继续、实例 B 正常完成并记录结果。API 分别使用 Responses、Anthropic Messages、Chat Completions。该核验验证生产执行链和实例隔离，API 内容仍为模拟回复，不能替代真实第三方接口测试。

## Android 真机与第三方 API

在 OnePlus PYB110 上保留既有签名和应用数据覆盖升级。

- 协作列表、资源设置保存及返回列表、参与者 / 子任务表单、计划保存可用；保存期间未触发推理。
- 创建三参与者、三个无依赖任务：Codex / `gpt-6.1-sol`、Claude Code / `claude-sonnet-5-5`、DeepSeek / `deepseek-v4-pro`，使用设备已有的第三方 API 配置，任务仅回复一句确认，不修改项目或调用工具。
- 实际观察全局运行 `2/2`，第三任务显示等待名额；之后三任务全部成功，达到“待验收 3/3”。
- Codex 返回“Codex 协作成功。”，DeepSeek 返回“DeepSeek 协作成功”；尝试详情保留结果和用量，DeepSeek 标明上下文不计入计费 Token。
- 验收结果保存在原协作记录，返回独立模式已复验打开同一主会话，显示“协作成果（已验收）”及三个任务结果。真机发现并修复了返回时过早设置 pendingThread 导致加载短路的问题。
- 升级未要求重新初始化共享环境或重新配置 API，原项目和会话保留。

本轮未进行长期后台压力测试、复杂项目多分支合并、所有模型和协议组合的付费请求测试，不能承诺所有 Android 设备及第三方 MCP 行为均已兼容。完整设计中的高级功能尚未全部实现。

## 发布一致性

发布 APK 使用项目既有证书（SHA-256 `0ed3ff23c0a576a8030df21f247ed8de9e89f06216d2732a3d45a8439bcb0c26`）。GitHub Actions 使用临时测试签名，CI 构建包不作为覆盖更新发布。

源码、标签、APK 版本及下载后 SHA-256 应按 [发布流程](UPDATES.md) 核对；私有配置、用户会话、开发机日志和签名材料不提交到 GitHub。
