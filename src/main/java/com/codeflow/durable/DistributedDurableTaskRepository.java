package com.codeflow.durable;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Multi-worker lease protocol with monotonically increasing fencing tokens. */
public interface DistributedDurableTaskRepository extends DurableTaskRepository {
    Optional<Lease> claim(String taskId, String workerId, Duration leaseDuration);
    Optional<Lease> claimNext(String workerId, Duration leaseDuration);
    boolean heartbeat(String taskId, String workerId, long fencingToken, Duration leaseDuration);
    boolean release(String taskId, String workerId, long fencingToken);
    DurableTaskRepository fenced(Lease lease);

    record Lease(String taskId, String workerId, long fencingToken,
                 Instant expiresAt, DurableTask task) { }
}
