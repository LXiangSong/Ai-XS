# Codex 的输入、中断与继续：源码核对

0.9.7-alpha 参考 OpenAI Codex 官方仓库的下列源码；取得的文件及 SHA-256 保存在本地 `.state/codex-execution-source-index.json`。

| 源文件 | 核对结果 |
| --- | --- |
| [chat_composer.rs](https://github.com/openai/codex/blob/main/codex-rs/tui/src/bottom_pane/chat_composer.rs) | 输入结果区分 Submitted/Queued；空文本且无附件返回 None，不会自动生成继续任务 |
| [input_flow.rs](https://github.com/openai/codex/blob/main/codex-rs/tui/src/chatwidget/input_flow.rs) | 明确区分即时提交和队列；仅空闲、已配置且没有未确认消息等阻塞时取下一条 |
| [input_queue.rs](https://github.com/openai/codex/blob/main/codex-rs/tui/src/chatwidget/input_queue.rs) | queued_user_messages、pending_steers、rejected_steers 独立保存；暂停派发与正在执行回合是不同状态 |
| [chatwidget.rs](https://github.com/openai/codex/blob/main/codex-rs/tui/src/chatwidget.rs) | AppCommand::Interrupt 用于中断当前执行；agent_turn_running 是回合运行状态，不表示可冻结现场的暂停 |
| [turn.rs](https://github.com/openai/codex/blob/main/codex-rs/app-server-protocol/src/protocol/v2/turn.rs) | TurnInterruptParams 指定 thread_id/turn_id；TurnSteerParams 用 expected_turn_id 校验当前回合 |

本 App 使用同样的中断语义：执行中空输入显示停止方块，点击中断当前执行并等待实际退出，状态为 stopping/interrupted。继续会在原会话提交后续回合；协作按已保留成果继续未完成成员，不把已结束的执行当作冻结的线程恢复。

本 App 的交互约定：运行中普通发送先排队，空闲时直接提交；显式“引入”才影响当前任务。停止后没有可自动接力的普通待发项时，空输入点击执行会续做原任务；0.9.8 起普通待发项优先自动接力，显式暂停和待核对项阻止派发。不能宣称 Codex TUI 默认使用完全相同的快捷键或空输入行为。

停止后发送新内容可直接开始后续工作，不会被旧“暂停状态”锁住；只有空输入继续才生成原任务的续做请求。停止旧协作后明确发送新任务，会结束旧轮剩余派发并保留原成果与历史，开始新轮。

任务详情里原先另有直接中断且不记录继续信息的“停止任务”，现已移除，输入区只保留一个随状态切换的执行按钮。模式切换中的“停止后进入协作”和协作管理中的整轮取消仍用于用户明确选择结束原执行，不与输入区的单按钮混用。
