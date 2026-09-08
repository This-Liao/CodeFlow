# ADR-0001：共享 Agent Runtime 与展示适配器

- 状态：Accepted
- 日期：2026-09-08

## 背景

TUI、Remote 和 Print 曾分别启动 Agent、记录 Trace、维护 Durable 状态和注入 Memory，导致能力只在某个入口生效，也使 Remote 的取消只中断事件消费线程。

## 决策

三个入口统一通过 `AgentRuntime` 启动一次运行。Runtime 负责真实 `AgentRunHandle`、Trace、Memory、Durable 生命周期和成功后的 Memory 整理；`SkillRuntimeSupport` 统一 Skill 目录与工具装配。展示适配器仅保留输入、渲染、Permission 和 AskUser 回调。

## 结果

- Remote/TUI 的取消会中断实际 Agent worker 和并行工具 Future。
- 所有入口生成相同结构的 Trace，并执行相同 Memory/Skill 生命周期。
- UI 控件不强求完全一致；Print 无主动取消控件，TUI 暂无 Durable 任务选择器。
