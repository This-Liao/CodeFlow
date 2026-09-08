# Context / Deferred Tool 消融基准

> 本报告由真实 Java 实现直接运行生成。任务集固定、无网络依赖，适合作为 CI 回归门禁；它衡量 Context 组装与工具发现，不把估算 Token 冒充模型账单。

## 配置

- 任务：24 条，覆盖 PLANNING、EXECUTING、VERIFYING、RECOVERING。
- 工具目录：46 个，其中包含内建工具和延迟加载的异构连接器。
- Context Policy 上限：12 个 Tool Schema。
- Estimated Input Tokens：按 Schema 字符数 / 4 估算，仅用于同一任务集的相对比较。

## 结果

| Variant | Success | Stage Accuracy | Initial Tool Recall | Initial Schemas | Schema Chars | Est. Tokens | Reduction vs Full | ToolSearch | Tool Error |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| full-tool-injection | 100.0% | 100.0% | 100.0% | 46.0 | 53366 | 13342 | 0.0% | 0 | 0 |
| deferred-tool | 100.0% | 100.0% | 87.2% | 17.0 | 18604 | 4651 | 65.1% | 5 | 0 |
| context-lexical | 100.0% | 100.0% | 84.6% | 12.0 | 12992 | 3248 | 75.7% | 6 | 0 |
| context-vector | 100.0% | 100.0% | 84.6% | 12.0 | 13051 | 3263 | 75.5% | 6 | 0 |
| context-hybrid | 100.0% | 100.0% | 84.6% | 12.0 | 13049 | 3262 | 75.5% | 6 | 0 |

## 解释边界

- Success 表示必需工具能在初始上下文中获得，或能通过 ToolSearch 在第二步精确恢复。
- Initial Tool Recall 衡量第一轮是否已经注入任务所需工具；它与 Schema 压缩率存在明确权衡。
- 当前固定集上三种 Context 策略的初始 Recall 相同；Hybrid 没有被包装成准确率提升，其价值是允许在阶段先验、词法匹配和向量相关度之间做可配置、可回退的工程权衡。
- 默认 Vector 使用离线 feature hashing，适合可复现基准；OpenAI-compatible Embedding 是显式可选项，失败时回退到 Lexical。
- 该基准不调用 LLM，因此不能替代真实模型任务成功率。真实模型评测作为独立的可选层运行，避免 CI 受随机性和外部 API 影响。
