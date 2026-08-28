# 真实模型 Coding Benchmark

> CodeFlow 使用真实模型在 12 个相互隔离的代码仓库中完成修改；脚本独立重跑测试，并校验源码确实变化且测试文件未被修改。报告不保存 Key、Base URL、Prompt 或模型回答正文。

模型：`deepseek-v4-flash`；时间：2026-08-28T06:27:13+00:00

| Task | Category | Result | Duration | Tool Calls | Tool Errors |
| --- | --- | ---: | ---: | ---: | ---: |
| `retry-backoff` | state-and-boundary | PASS | 75.4s | 17 | 1 |
| `pagination-bounds` | input-validation | PASS | 45.7s | 10 | 0 |
| `slug-normalization` | text-normalization | PASS | 40.2s | 11 | 0 |
| `secret-redaction` | security | PATCH PASS / AGENT INCOMPLETE | 203.2s | 0 | 0 |
| `duration-parser` | parsing | PASS | 28.9s | 8 | 0 |
| `deep-config-merge` | data-structures | PASS | 76.8s | 15 | 1 |
| `dependency-order` | graph-algorithm | PASS | 292.1s | 21 | 0 |
| `stable-chunking` | collection-processing | PASS | 52.1s | 8 | 1 |
| `http-retry-classifier` | policy | PASS | 100.1s | 22 | 3 |
| `circuit-breaker` | state-machine | PASS | 44.2s | 11 | 1 |
| `header-normalization` | protocol-data | PASS | 54.4s | 9 | 0 |
| `safe-path-join` | filesystem-security | PASS | 77.2s | 12 | 1 |

## 汇总

- 端到端 Agent 成功率：11/12（91.7%）
- 正确 Patch：12/12（100.0%）
- 正常返回终态的 Agent 进程：11/12
- 独立测试通过：12/12
- 测试文件保持不变：12/12
- p50 / p95 任务耗时：54.4s / 292.1s
- Tool Call / Tool Error：144 / 8

该套件覆盖边界处理、输入校验、文本与配置处理、图算法、状态机、协议数据和路径安全。“PATCH PASS / AGENT INCOMPLETE”表示源码和独立测试正确，但 Agent 进程没有正常返回终态；它仍按端到端失败计数。Tool Error 包含 Agent 主动运行失败测试后再修复的诊断步骤。

样本规模仍然有限，结果受模型版本、采样与网络状态影响，不作为默认 CI 门禁。
