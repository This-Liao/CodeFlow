# 真实 Agent Crash + Resume

> 两个 CodeFlow CLI 进程调用同一个真实模型。进程 1 在完成代码修改并写入安全 Turn Checkpoint 后被父进程强制终止；进程 2 使用同一 Task ID 和 Session 日志恢复。脚本最后独立运行测试。

| 证据 | 结果 |
| --- | --- |
| 模型 | `deepseek-v4-flash` |
| Task | `cf-39d28481db408c52` |
| 崩溃前状态 / Checkpoint | EXECUTING / turn 5 |
| 外部终止 | exit=1 |
| 新进程接管 | true |
| Session 历史复用 | 11 条消息 |
| 恢复进程重复修改 | 0 次 |
| 最终状态 | COMPLETED |
| 独立测试 | PASS |
| 总耗时 | 37.9s |

机器可读证据见 `docs/demo/crash-resume.json`。报告不包含 Key、Base URL、Prompt、源码内容或模型回答正文。
