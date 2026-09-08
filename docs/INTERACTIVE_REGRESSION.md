# Remote UI 真实交互回归

该套件不调用 Print Mode。每条任务都会启动真实 Remote Server，通过浏览器同协议的
WebSocket 发送请求，消费 Agent/Tool/Durable 事件，再由独立进程重跑测试。

| Task | Result | Durable | Traces | Independent test | Duration |
| --- | ---: | ---: | ---: | ---: | ---: |
| `retry-backoff` | PASS | COMPLETED | 1 | PASS | 9.8s |
| `pagination-bounds` | PASS | COMPLETED | 1 | PASS | 11.6s |
| `slug-normalization` | PASS | COMPLETED | 1 | PASS | 9.1s |
| `secret-redaction` | PASS | COMPLETED | 1 | PASS | 61.2s |
| `duration-parser` | PASS | COMPLETED | 1 | PASS | 9.8s |
| `deep-config-merge` | PASS | COMPLETED | 1 | PASS | 11.2s |
| `dependency-order` | PASS | COMPLETED | 1 | PASS | 10.2s |
| `stable-chunking` | PASS | COMPLETED | 1 | PASS | 8.6s |
| `http-retry-classifier` | PASS | COMPLETED | 1 | PASS | 9.9s |
| `circuit-breaker` | PASS | COMPLETED | 1 | PASS | 11.2s |
| `header-normalization` | PASS | COMPLETED | 1 | PASS | 8.7s |
| `safe-path-join` | PASS | COMPLETED | 1 | PASS | 25.0s |

- 交互任务：12/12 通过
- Patch 正确：12/12
- Durable 生命周期完整：12/12
- 生成 Trace：12/12

真实模型结果受模型版本和网络状态影响，因此默认不作为每次 PR 的强制 CI 门禁。
