package com.codeflow.durable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Storage contract shared by the local crash-safe and PostgreSQL backends. */
public interface DurableTaskRepository {
    DurableTask create(String prompt, String sessionId, String owner, int maxAttempts);
    Optional<DurableTask> get(String id);
    List<DurableTask> list(boolean recoverableOnly);
    DurableTask transition(String id, DurableTaskState next, String reason, Long expectedVersion);
    DurableTask resume(String id, String reason);
    DurableTask recover(String id, String owner, String reason, Long expectedVersion);
    DurableTask checkpoint(String id, Map<String, Object> checkpoint, String reason);
    DurableTask addArtifact(String id, String name, String uri, String mediaType,
                            Map<String, Object> metadata);
}
