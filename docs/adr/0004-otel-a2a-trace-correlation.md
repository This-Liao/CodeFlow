# ADR-0004：OpenTelemetry 与 A2A Trace Correlation

- 状态：Accepted
- 日期：2026-09-08

## 背景

本地 JSON Trace 能做回归分类，但不能解释一次任务跨 Java Runtime、工具、A2A HTTP 与 Python/LangGraph 后的耗时和错误归属。

## 决策

Java 为 Agent Run、LLM Stream、Tool、A2A Discover/Delegate 建立 Span，并在虚拟线程和并行工具执行器中显式传播 Context。A2A 请求注入 W3C `traceparent`；Python FastAPI 提取后将 Context 传入后台 LangGraph Task。未配置 Collector 时只生成/传播 ID，配置 OTLP endpoint 后才导出。

Span 不记录 Prompt、源码、模型回答、Tool Result 或凭据，也不传播 Baggage。

## 结果

真实 Java → Python 进程验证返回相同 Trace ID，`traceCorrelated=true`。Crash/Resume 的跨进程 Span Link 尚未持久化，现阶段通过 Durable Task ID 查询关联。
