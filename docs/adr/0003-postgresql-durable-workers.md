# ADR-0003：PostgreSQL Durable 多 Worker

- 状态：Accepted（可选后端）
- 日期：2026-09-08

## 背景

原子 JSON 快照适合单机 Crash/Resume，却不能让多进程安全共享任务。仅使用数据库行锁也无法阻止网络分区后的旧 Worker 继续写入。

## 决策

保留本地 JSON 默认后端，并通过 `DurableTaskRepository` 提供 PostgreSQL 实现。领取任务使用 `FOR UPDATE SKIP LOCKED`；每次领取生成单调递增 fencing token。Runtime 获取一个 fenced Repository 视图，所有 transition、checkpoint 和 artifact 写入必须同时匹配 Worker、token 和有效租约，并由心跳续期。

## 结果

- 多个 TUI/Remote/Print 进程可共享任务表并各自处理不同任务。
- 旧 token 的写入在 SQL 层失败，租约丢失会取消实际 Agent worker。
- 本实现尚未使用连接池，也不承诺外部工具副作用 exactly-once。
