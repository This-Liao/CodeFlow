# OpenTelemetry 与 A2A Trace Correlation 决策

## 结论

值得实现，但不应先于真实 Coding Benchmark 和真实 Crash + Resume 证据。CodeFlow 目前已经能够回答“任务是否成功、调用了哪些工具、Token 与延迟是多少”；下一步 OpenTelemetry 应解决的是“一个长尾失败跨 Java 主 Agent、A2A HTTP 和 Python Agent 时，时间消耗和错误具体发生在哪里”。

当前代码没有宣称已经接入 OpenTelemetry。本文件定义后续最小实现范围，避免只增加依赖或导出没有关联关系的 Span。

## 最小范围

- 默认关闭、无 Collector 时保持 No-op，不改变现有 CLI 行为。
- 使用 W3C Trace Context，在 CodeFlow 拥有且显式配置的 A2A 端点之间传播 `traceparent`。
- Java 与 Python 至少形成以下 Span：`agent.run`、`agent.turn`、`tool.execute`、`a2a.delegate`、`a2a.remote.execute`。
- Span 只记录任务 ID、Agent 名、工具名、状态、Token 数和耗时；不记录 Prompt、模型回答、源码、Tool Result、API Key 或任意 PII。
- 不传播 Baggage；来自不可信外部端点的 Trace Context 必须校验，并允许按 Agent 配置关闭接收。
- OTLP Exporter 作为可选运行时配置，而不是默认打包并强制连接外部后端。

## 验收标准

1. Java → Python A2A 验证中，两端 Span 具有同一 Trace ID，远端 Span 的 Parent 指向 Java A2A Client Span。
2. Agent Crash + Resume 产生两个 Process Span，并通过 Durable Task ID 建立 Link；不伪造跨进程 Parent。
3. 禁用遥测时，现有 205 项测试、A2A、Durable Recovery 和 Context 门禁结果不变。
4. 自动化测试检查导出属性中不存在 Prompt、源码内容、凭据与 Tool Result。
5. 用 Coding Benchmark 中“Patch 正确但 Agent 未返回终态”的案例证明 Trace 能定位长尾阶段，而不是只展示一张空的 Trace 图。

OpenTelemetry Java 官方将 Trace、Metric 与 Log 标为稳定，并建议库只依赖 API、由应用安装 SDK；CodeFlow 后续实现应遵循这种 API-first、Exporter 可选的方式。上下文传播只应发生在受信任的自有 A2A 边界。
