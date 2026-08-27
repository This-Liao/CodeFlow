# CodeFlow 工程验证

> 这是由 `scripts/record_e2e_demo.py` 在真实端到端运行后生成的结果，不是手工填写的演示数据。

## 最近一次结果

| 验证项 | 实测结果 |
| --- | --- |
| 时间（UTC） | 2026-08-27T06:27:29+00:00 |
| Gradle 构建 | PASS |
| JUnit | 200 项；198 通过；0 失败；2 跳过 |
| 可执行 JAR | 82.85 MiB |
| A2A Agent | CodeFlow Python Static Analysis Agent |
| 协议 | A2A 1.0 / HTTP+JSON |
| 任务终态 | TASK_STATE_COMPLETED |
| 返回 Artifact | 1 |
| 实际扫描 Java 文件 | 191 |
| 静态分析候选问题 | 47 |
| Java→Python 端到端耗时 | 311 ms |
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
```

## 复现

请从仓库根目录执行 README“工程验证”中的三条命令。脚本仅在构建、全部测试和 A2A Artifact 校验均成功后覆盖本文件、JSON 结果与 GIF。
