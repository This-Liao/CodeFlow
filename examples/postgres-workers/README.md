# PostgreSQL 多 Worker 验证

此示例只用于本地开发，Compose 中的口令不能用于生产。启动数据库后，将连接信息通过环境变量传入，配置文件不保存真实凭据：

```powershell
docker compose -f examples/postgres-workers/compose.yaml up -d
$env:CODEFLOW_TEST_POSTGRES_URL = "jdbc:postgresql://127.0.0.1:5432/codeflow"
$env:CODEFLOW_TEST_POSTGRES_USER = "codeflow"
$env:CODEFLOW_TEST_POSTGRES_PASSWORD = "codeflow-dev-only"
.\gradlew.bat test --tests com.codeflow.durable.PostgresDurableTaskRepositoryTest
```

测试创建两个 Repository 实例并并发语义地领取两条任务，验证 `FOR UPDATE SKIP LOCKED` 不会重复分配；随后释放并重新领取同一任务，确认旧 fencing token 无法再写 Checkpoint。
