# OpenTelemetry 与 A2A Trace Correlation

## 当前实现

CodeFlow 已在共享 Runtime 中接入 OpenTelemetry，并覆盖以下 Span：

```text
codeflow.agent.run
├── codeflow.llm.stream
├── codeflow.tool.execute
└── codeflow.a2a.delegate
    ├── codeflow.a2a.discover
    └── W3C traceparent → Python FastAPI SERVER span
        └── codeflow.a2a.task.execute（异步 LangGraph）
```

Agent 根 Context 会显式传入虚拟线程；并行只读工具把当前 Context 包装进每个 `Callable`。A2A Client 在 Agent Card、消息提交和轮询请求上注入 W3C `traceparent`。Python 服务从请求头提取 Context，并在 HTTP 响应结束后继续把父上下文传给后台 LangGraph Task。

## 配置

不配置 Collector 时，Java/Python 仍生成并传播有效 Trace ID，但不会进行遥测网络请求。导出到同一个 OTLP/HTTP Collector：

```powershell
$env:OTEL_EXPORTER_OTLP_ENDPOINT = "http://localhost:4318"
$env:OTEL_SERVICE_NAME = "codeflow-java" # Python 进程可单独设为 codeflow-python-a2a
```

也可直接设置带 `/v1/traces` 的 `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`。`OTEL_SDK_DISABLED=true` 会完全关闭 SDK 与传播。

## 数据边界

Span 仅记录运行模式、Session/Durable Task ID、模型名、协议、工具名、状态、Token 数与错误摘要。不会写入 Prompt、模型回答、源码、工具参数、Tool Result、API Key 或 Baggage。远程 A2A 返回内容仍经过不可信输入边界后才交给父 Agent。

## 真实进程验证

2026-09-08 的本机验证启动真实 Python/FastAPI/LangGraph 进程，再由 fat JAR 执行 Java A2A Client：

| 指标 | 结果 |
|---|---:|
| A2A 协议 | 1.0 / HTTP+JSON |
| Python Task | `TASK_STATE_COMPLETED` |
| 扫描 Java 文件 | 210 |
| Artifact | 1 |
| Java/Python Trace ID | `0affb88f875df1a6fe77e41fa51b6a89` |
| Correlation | PASS |

机器可读证据见 [`docs/observability/latest.json`](observability/latest.json)。此外，`A2aClientTest` 的真实 HTTP Server 会断言请求包含非零 W3C Trace/Span ID。

## 尚未覆盖

- Crash 前后两个进程目前依赖相同 Durable Task ID 查询，尚未持久化 OpenTelemetry Span Link。
- 当前未提交 Collector/Jaeger 截图；仓库验证的是实际传播与 Trace ID 一致性，不用 UI 截图替代协议断言。
- 暂不传播 Baggage，避免把业务字段无边界地带到远程 Agent。
