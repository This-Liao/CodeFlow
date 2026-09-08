package com.codeflow.runtime;

import com.codeflow.agent.Agent;
import com.codeflow.agent.AgentEvent;
import com.codeflow.agent.AgentRunHandle;
import com.codeflow.config.ProviderConfig;
import com.codeflow.conversation.ConversationManager;
import com.codeflow.durable.DurableTask;
import com.codeflow.durable.DurableTaskRepository;
import com.codeflow.durable.DistributedDurableTaskRepository;
import com.codeflow.durable.DurableTaskState;
import com.codeflow.durable.DurableTaskStore;
import com.codeflow.eval.TraceRecorder;
import com.codeflow.llm.LlmClient;
import com.codeflow.llm.StreamEvent;
import com.codeflow.memory.MemoryConsolidator;
import com.codeflow.memory.MemoryManager;
import com.codeflow.memory.MemoryRecall;
import com.codeflow.observability.Telemetry;
import com.codeflow.session.SessionManager;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Shared lifecycle runtime behind TUI, Remote and Print adapters.
 *
 * <p>Presentation adapters still own their UI-specific permission dialogs and
 * rendering, but every real conversation run now follows the same cancellation,
 * trace, durable checkpoint and memory lifecycle.</p>
 */
public final class AgentRuntime {
    private final Agent agent;
    private final LlmClient client;
    private final ProviderConfig provider;
    private final String workDir;
    private final String mode;
    private final MemoryManager memoryManager;
    private final MemoryConsolidator memoryConsolidator;
    private final DurableTaskRepository durableStore;
    private final Consumer<DurableTask> taskListener;

    public AgentRuntime(Agent agent, LlmClient client, ProviderConfig provider,
                        String workDir, String mode, MemoryManager memoryManager,
                        DurableTaskRepository durableStore, Consumer<DurableTask> taskListener) {
        this.agent = agent;
        this.client = client;
        this.provider = provider;
        this.workDir = workDir;
        this.mode = mode;
        this.memoryManager = memoryManager;
        this.memoryConsolidator = memoryManager == null ? null : new MemoryConsolidator(workDir);
        this.durableStore = durableStore == null ? new DurableTaskStore(workDir) : durableStore;
        this.taskListener = taskListener == null ? ignored -> { } : taskListener;
    }

    public RuntimeRun start(ConversationManager conversation, StartOptions options) {
        return start(conversation, options, null);
    }

    public synchronized RuntimeRun start(ConversationManager conversation, StartOptions options,
                                         BlockingQueue<AgentEvent> callerQueue) {
        if (conversation == null) throw new IllegalArgumentException("conversation is required");
        if (options == null) throw new IllegalArgumentException("start options are required");

        DurableLifecycle durable = options.durable()
                ? beginDurable(options)
                : new DurableLifecycle(null, durableStore, null, null);

        TraceRecorder trace = new TraceRecorder(workDir);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("mode", mode);
        metadata.put("protocol", provider.getProtocol());
        if (durable.task() != null) metadata.put("durableTaskId", durable.task().getId());
        trace.begin(options.prompt(), provider.getModel(), options.sessionId(), metadata);
        Span runSpan = Telemetry.tracer().spanBuilder("codeflow.agent.run")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("codeflow.mode", mode)
                .setAttribute("codeflow.session.id", options.sessionId())
                .setAttribute("codeflow.durable", options.durable())
                .setAttribute("gen_ai.request.model", provider.getModel())
                .startSpan();
        if (durable.task() != null) {
            runSpan.setAttribute("codeflow.durable_task.id", durable.task().getId());
        }
        AtomicBoolean spanEnded = new AtomicBoolean();

        AgentRunHandle handle;
        try (Scope ignored = runSpan.makeCurrent()) {
            prepareMemory(options.prompt());
            agent.setEventObserver(event -> {
                try (Scope eventScope = runSpan.makeCurrent()) {
                    trace.accept(event);
                    durable.accept(event);
                    if (event instanceof AgentEvent.ErrorEvent failure) {
                        runSpan.addEvent("agent.error");
                        runSpan.setAttribute("codeflow.error.message", failure.message());
                    }
                    if (event instanceof AgentEvent.LoopComplete complete && complete.totalTurns() > 0) {
                        afterSuccessfulRun(conversation);
                    }
                    if (event instanceof AgentEvent.CanceledEvent) {
                        runSpan.setStatus(StatusCode.ERROR, "canceled");
                        endSpanOnce(runSpan, spanEnded);
                    } else if (event instanceof AgentEvent.LoopComplete) {
                        endSpanOnce(runSpan, spanEnded);
                    }
                }
            });
            handle = callerQueue == null
                    ? agent.start(conversation)
                    : agent.start(conversation, callerQueue);
            durable.attach(handle);
        } catch (RuntimeException failure) {
            runSpan.recordException(failure);
            runSpan.setStatus(StatusCode.ERROR, failure.getMessage());
            endSpanOnce(runSpan, spanEnded);
            throw failure;
        }
        return new RuntimeRun(handle, durable.task() == null ? null : durable.task().getId(),
                options.sessionId(), trace);
    }

    /**
     * Rebuilds the conversation belonging to a recoverable durable task.
     * Ownership/state transition occurs when {@link #start} is called.
     */
    public ResumeContext restore(String taskId) {
        DurableTask task = durableStore.get(taskId)
                .orElseThrow(() -> new IllegalArgumentException("durable task not found: " + taskId));
        if (task.getState().isTerminal()) {
            throw new IllegalStateException("terminal task cannot be resumed: " + task.getState());
        }
        String sessionId = task.getSessionId();
        ConversationManager conversation = SessionManager.rebuildConversation(
                SessionManager.loadSession(workDir, sessionId));
        String reminder = "恢复持久任务 " + task.getId()
                + "。请从最后一个安全检查点继续，不要重复已经完成的修改。\n"
                + "持久化状态：" + task.getState() + "\n"
                + "检查点：" + task.getCheckpoint();
        conversation.addUserMessage(reminder);
        SessionManager.saveMessage(workDir, sessionId, "user", reminder);
        return new ResumeContext(task, conversation, reminder);
    }

    public List<DurableTask> recoverableTasks() {
        return durableStore.list(true);
    }

    public DurableTaskRepository durableStore() {
        return durableStore;
    }

    private DurableLifecycle beginDurable(StartOptions options) {
        DistributedDurableTaskRepository distributed =
                durableStore instanceof DistributedDurableTaskRepository value ? value : null;
        DistributedDurableTaskRepository.Lease lease = null;
        try {
            DurableTask task;
            DurableTaskRepository runStore = durableStore;
            if (options.resumeTaskId() != null && !options.resumeTaskId().isBlank()) {
                task = durableStore.get(options.resumeTaskId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "durable task not found: " + options.resumeTaskId()));
                if (distributed != null) {
                    String leasedTaskId = task.getId();
                    lease = distributed.claim(leasedTaskId, options.owner(), null)
                            .orElseThrow(() -> new IllegalStateException(
                                    "durable task is leased by another worker: " + leasedTaskId));
                    runStore = distributed.fenced(lease);
                }
                if (task.getState() == DurableTaskState.PAUSED
                        || task.getState() == DurableTaskState.WAITING_APPROVAL
                        || task.getState() == DurableTaskState.FAILED_RETRYABLE) {
                    task = runStore.resume(task.getId(), "由 " + mode + " 恢复");
                } else if (!task.getState().isTerminal()) {
                    task = runStore.recover(task.getId(), options.owner(),
                            "运行时重新接管", task.getVersion());
                }
            } else {
                task = durableStore.create(options.prompt(), options.sessionId(), options.owner(), 3);
                if (distributed != null) {
                    String leasedTaskId = task.getId();
                    lease = distributed.claim(leasedTaskId, options.owner(), null)
                            .orElseThrow(() -> new IllegalStateException(
                                    "new durable task lease could not be acquired: " + leasedTaskId));
                    runStore = distributed.fenced(lease);
                }
                task = runStore.transition(task.getId(), DurableTaskState.PLANNING,
                        "任务已接收", task.getVersion());
                task = runStore.transition(task.getId(), DurableTaskState.EXECUTING,
                        "上下文已初始化", task.getVersion());
            }
            taskListener.accept(task);
            return new DurableLifecycle(task, runStore, distributed, lease);
        } catch (RuntimeException e) {
            if (distributed != null && lease != null) {
                try {
                    distributed.release(lease.taskId(), lease.workerId(), lease.fencingToken());
                } catch (RuntimeException ignored) { }
            }
            throw new IllegalStateException("durable execution initialization failed: " + e.getMessage(), e);
        }
    }

    private void prepareMemory(String query) {
        if (memoryManager == null) return;
        agent.setMemoryContent(memoryManager.buildSystemReminder());
        agent.setMemoryRecallFuture(prefetchRelevantMemories(query));
    }

    private CompletableFuture<String> prefetchRelevantMemories(String query) {
        if (memoryManager == null || provider == null) {
            return CompletableFuture.completedFuture("");
        }
        return CompletableFuture.supplyAsync(() -> {
            MemoryRecall.SelectorFn selector = (systemPrompt, userMessage) -> {
                LlmClient sideClient = LlmClient.create(provider, systemPrompt);
                ConversationManager mini = new ConversationManager();
                mini.addUserMessage(userMessage);
                BlockingQueue<StreamEvent> events = sideClient.stream(mini, null);
                var output = new StringBuilder();
                while (true) {
                    StreamEvent event = events.take();
                    if (event instanceof StreamEvent.TextDelta text) output.append(text.text());
                    if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) break;
                }
                return output.toString();
            };
            return MemoryRecall.renderReminder(MemoryRecall.findRelevantMemories(
                    query, memoryManager.userMemDir(), memoryManager.projectMemDir(),
                    null, null, selector));
        }, runnable -> {
            Thread worker = Thread.ofVirtual().name("memory-recall-prefetch").start(runnable);
            Thread.ofVirtual().start(() -> {
                try {
                    if (!worker.join(Duration.ofSeconds(8))) worker.interrupt();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });
    }

    private void afterSuccessfulRun(ConversationManager conversation) {
        if (memoryManager == null) return;
        if (memoryManager.shouldExtract()) {
            Thread.ofVirtual().name("memory-extraction").start(() ->
                    memoryManager.extract(client, conversation));
        }
        if (memoryConsolidator != null) {
            memoryConsolidator.maybeRun(client, conversation, provider.getProtocol());
        }
    }

    private static void endSpanOnce(Span span, AtomicBoolean ended) {
        if (ended.compareAndSet(false, true)) span.end();
    }

    public record StartOptions(String prompt, String sessionId, boolean durable,
                               String resumeTaskId, String owner) {
        public StartOptions {
            if (prompt == null) prompt = "";
            if (owner == null || owner.isBlank()) owner = "codeflow-" + ProcessHandle.current().pid();
        }

        public static StartOptions interactive(String prompt, String sessionId, String mode) {
            return new StartOptions(prompt, sessionId, true, null,
                    mode + "-" + ProcessHandle.current().pid() + "-"
                            + java.util.UUID.randomUUID().toString().substring(0, 8));
        }
    }

    public record RuntimeRun(AgentRunHandle handle, String durableTaskId,
                             String sessionId, TraceRecorder trace) { }

    public record ResumeContext(DurableTask task, ConversationManager conversation,
                                String resumeReminder) { }

    private final class DurableLifecycle implements Consumer<AgentEvent> {
        private DurableTask task;
        private final DurableTaskRepository runStore;
        private final DistributedDurableTaskRepository distributed;
        private final DistributedDurableTaskRepository.Lease lease;
        private volatile boolean leaseActive;
        private volatile AgentRunHandle handle;
        private boolean error;

        private DurableLifecycle(DurableTask task, DurableTaskRepository runStore,
                                 DistributedDurableTaskRepository distributed,
                                 DistributedDurableTaskRepository.Lease lease) {
            this.task = task;
            this.runStore = runStore;
            this.distributed = distributed;
            this.lease = lease;
            this.leaseActive = lease != null;
        }

        void attach(AgentRunHandle handle) {
            this.handle = handle;
            if (lease != null) {
                Thread.ofVirtual().name("durable-lease-heartbeat").start(this::heartbeatLoop);
            }
        }

        synchronized DurableTask task() {
            return task;
        }

        @Override
        public synchronized void accept(AgentEvent event) {
            if (task == null || task.getState().isTerminal()) return;
            try {
                if (event instanceof AgentEvent.PermissionRequestEvent permission) {
                    transition(DurableTaskState.WAITING_APPROVAL, "等待用户授权");
                    permission.future().whenComplete((ignored, failure) -> {
                        synchronized (DurableLifecycle.this) {
                            if (task != null && task.getState() == DurableTaskState.WAITING_APPROVAL) {
                                update(runStore.resume(task.getId(), "用户已处理授权请求"));
                            }
                        }
                    });
                } else if (event instanceof AgentEvent.ToolResultEvent tool) {
                    var checkpoint = new LinkedHashMap<String, Object>();
                    checkpoint.put("lastTool", tool.toolName());
                    checkpoint.put("lastToolId", tool.toolId());
                    checkpoint.put("toolError", tool.isError());
                    checkpoint.put("elapsedSeconds", tool.elapsed());
                    update(runStore.checkpoint(task.getId(), checkpoint, "工具执行完成"));
                } else if (event instanceof AgentEvent.TurnComplete turn) {
                    update(runStore.checkpoint(task.getId(), Map.of("turn", turn.turn()),
                            "Agent 轮次完成"));
                } else if (event instanceof AgentEvent.RetryEvent retry) {
                    update(runStore.checkpoint(task.getId(),
                            Map.of("retryReason", retry.reason(), "waitMs", retry.waitMs()),
                            "等待重试"));
                } else if (event instanceof AgentEvent.ErrorEvent failure) {
                    error = true;
                    transition(DurableTaskState.FAILED_RETRYABLE, failure.message());
                } else if (event instanceof AgentEvent.CanceledEvent canceled) {
                    transition(DurableTaskState.CANCELED, canceled.reason());
                } else if (event instanceof AgentEvent.LoopComplete complete
                        && complete.totalTurns() > 0 && !error) {
                    if (task.getState() == DurableTaskState.EXECUTING) {
                        transition(DurableTaskState.VERIFYING, "Agent 循环结束，执行收尾验证");
                    }
                    if (task.getState() == DurableTaskState.VERIFYING) {
                        transition(DurableTaskState.COMPLETED, "任务完成");
                    }
                }
                if (task != null && task.getState().isTerminal()) closeLease();
            } catch (RuntimeException ignored) {
                // Durable bookkeeping must not break the Agent event stream.
            }
        }

        private void transition(DurableTaskState state, String reason) {
            if (task.getState() == state) return;
            if (!task.getState().canTransitionTo(state)) return;
            update(runStore.transition(task.getId(), state, reason, task.getVersion()));
        }

        private void update(DurableTask value) {
            task = value;
            taskListener.accept(value);
        }

        private void heartbeatLoop() {
            long initialMillis = Math.max(5_000,
                    Duration.between(Instant.now(), lease.expiresAt()).toMillis());
            long intervalMillis = Math.max(1_000, initialMillis / 3);
            while (leaseActive) {
                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!leaseActive) return;
                boolean renewed;
                try {
                    renewed = distributed.heartbeat(lease.taskId(), lease.workerId(),
                            lease.fencingToken(), null);
                } catch (RuntimeException failure) {
                    renewed = false;
                }
                if (!renewed) {
                    leaseActive = false;
                    AgentRunHandle current = handle;
                    if (current != null) current.cancel("Worker lease lost; task left recoverable");
                    return;
                }
            }
        }

        private void closeLease() {
            if (!leaseActive || lease == null) return;
            leaseActive = false;
            try {
                distributed.release(lease.taskId(), lease.workerId(), lease.fencingToken());
            } catch (RuntimeException ignored) { }
        }
    }
}
