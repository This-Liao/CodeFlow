package com.codeflow.agent;

import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A cancellable handle for one Agent loop.
 *
 * <p>The queue is consumed by TUI, Remote and Print adapters, while cancellation
 * targets the virtual thread that actually owns the loop. This prevents a UI
 * adapter from merely stopping its event consumer while tools continue running
 * in the background.</p>
 */
public final class AgentRunHandle {
    private final BlockingQueue<AgentEvent> events;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Consumer<AgentEvent> observer;
    private volatile Thread worker;
    private volatile String cancelReason = "用户取消";

    AgentRunHandle(BlockingQueue<AgentEvent> events, Consumer<AgentEvent> observer) {
        this.events = Objects.requireNonNull(events, "events");
        this.observer = observer == null ? ignored -> { } : observer;
    }

    void attach(Thread worker) {
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    public BlockingQueue<AgentEvent> events() {
        return events;
    }

    public boolean cancel(String reason) {
        if (!cancelled.compareAndSet(false, true)) return false;
        if (reason != null && !reason.isBlank()) cancelReason = reason;
        AgentEvent event = new AgentEvent.CanceledEvent(cancelReason);
        try { observer.accept(event); } catch (RuntimeException ignored) { }
        events.offer(event);
        Thread current = worker;
        if (current != null) current.interrupt();
        return true;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public String cancelReason() {
        return cancelReason;
    }

    public boolean isAlive() {
        Thread current = worker;
        return current != null && current.isAlive();
    }
}
