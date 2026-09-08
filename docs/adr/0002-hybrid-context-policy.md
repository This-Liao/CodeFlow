# ADR-0002：Hybrid Context Policy

- 状态：Accepted
- 日期：2026-09-08

## 背景

纯阶段规则可复现但对措辞变化不敏感；纯向量相似度会弱化关键工具保底，也引入外部模型依赖。

## 决策

最终分数由任务阶段先验、词法相关度和向量相似度共同构成。核心工具始终保留，超出初始预算的工具可由 `ToolSearch` 恢复。默认向量提供器是离线 feature hashing；可显式切换到 OpenAI-compatible Embedding，失败时回退到词法策略。

## 结果

24 条固定任务中 Hybrid 保持 100% 最终成功并减少 75.5% Schema 字符，但初始 Tool Recall 未超过单独策略。它当前是预算与鲁棒性机制，不被描述为准确率提升。
