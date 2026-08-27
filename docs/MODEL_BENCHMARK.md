# 真实模型 Context Benchmark

> CodeFlow JAR 调用实际付费模型，在隔离只读 Fixture 上运行；报告不包含 Key、Base URL 或回答正文。

模型：deepseek-v4-flash；任务：4 条；时间：2026-08-27T08:37:00+00:00

| Variant | Success | Avg Prompt Tokens | Avg Uncached Input | Avg Cache Read | Output Tokens | p95 Latency | Tool Calls | Tool Errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| full-tool-context | 100.0% | 7020 | 108 | 6912 | 143 | 26062 ms | 7 | 0 |
| stage-aware-context | 100.0% | 5981 | 2941 | 3040 | 100 | 8875 ms | 6 | 0 |

在本次固定任务集上，Context Policy 将平均完整 Prompt Token 减少 14.8%，p95 延迟减少 65.9%。

结果受模型版本与采样影响，作为 Semantic Layer 证据，不替代 CI 确定性门禁。
