# CodeFlow 工程验证

> 这是由 `scripts/record_e2e_demo.py` 在真实端到端运行后生成的结果，不是手工填写的演示数据。

## 最近一次结果

| 验证项 | 实测结果 |
| --- | --- |
| 时间（UTC） | 2026-08-28T07:23:26+00:00 |
| Gradle 构建 | PASS |
| JUnit | 205 项；203 通过；0 失败；2 跳过 |
| 可执行 JAR | 82.88 MiB |
| A2A Agent | CodeFlow Python Static Analysis Agent |
| 协议 | A2A 1.0 / HTTP+JSON |
| 任务终态 | TASK_STATE_COMPLETED |
| 返回 Artifact | 1 |
| 实际扫描 Java 文件 | 194 |
| 静态分析候选问题 | 48 |
| Java→Python 端到端耗时 | 324 ms |
| Crash-Recovery | PASS；EXECUTING → COMPLETED |
| Checkpoint 复用 | true |
| 重复 Tool Call | 0 |
| 文件哈希保持 | true |
| 新 JVM 恢复耗时 | 125 ms |
| Context 基准 | PASS；24 条任务 / 46 个工具 |
| Context 任务成功率 | 100.0% |
| 阶段识别准确率 | 100.0% |
| Tool Schema 减少 | 75.7% |
| ToolSearch 错误 | 0 |
| 环境 | Windows 11 (AMD64); java version "22.0.2" 2024-07-16 |

## 实际验证链路

```text
Gradle clean/test/shadowJar
  → Python/LangGraph Agent 启动
  → Java Host 获取 Agent Card
  → A2A message:send 创建任务
  → tasks/{id} 状态轮询
  → TASK_STATE_COMPLETED
  → Markdown + JSON Artifact 校验

JVM-1 执行真实 EditFile
  → 原子写入 Checkpoint + Event
  → 外部强制终止 JVM-1
  → JVM-2 加载任务与 Checkpoint
  → 跳过已完成 Tool Call
  → 校验文件哈希与调用审计
  → COMPLETED

24 条固定任务 / 46 个工具
  → full-tool-injection
  → deferred-tool
  → stage-aware Context Policy
  → 校验成功率、阶段识别、Schema 压缩与 ToolSearch 错误
  → PASS
```

## 复现

请从仓库根目录执行 README“工程验证”中的三条命令。脚本仅在构建、全部测试、A2A Artifact、Crash-Recovery 和 Context 回归门禁均成功后覆盖本文件、JSON 结果与 GIF。
