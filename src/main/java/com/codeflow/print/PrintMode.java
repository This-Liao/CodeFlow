
package com.codeflow.print;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codeflow.agent.Agent;
import com.codeflow.agent.AgentEvent;
import com.codeflow.config.AppConfig;
import com.codeflow.config.HookConfig;
import com.codeflow.config.McpServerConfig;
import com.codeflow.config.ProviderConfig;
import com.codeflow.conversation.ConversationManager;
import com.codeflow.filehistory.FileHistory;
import com.codeflow.hook.HookEngine;
import com.codeflow.llm.LlmClient;
import com.codeflow.mcp.McpManager;
import com.codeflow.memory.MemoryManager;
import com.codeflow.permission.PermissionChecker;
import com.codeflow.permission.PermissionMode;
import com.codeflow.permission.PermissionResponse;
import com.codeflow.prompt.PromptBuilder;
import com.codeflow.session.SessionManager;
import com.codeflow.skill.SkillCatalog;
import com.codeflow.runtime.AgentRuntime;
import com.codeflow.runtime.SkillRuntimeSupport;
import com.codeflow.subagent.AgentTool;
import com.codeflow.subagent.SubAgentTaskManager;
import com.codeflow.task.TaskList;
import com.codeflow.task.TaskTools;
import com.codeflow.teams.TeamManager;
import com.codeflow.tool.ToolRegistry;
import com.codeflow.tool.impl.AskUserTool;
import com.codeflow.tool.impl.ToolSearchTool;
import com.codeflow.worktree.WorktreeManager;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Print 模式（-p）：非交互式运行 Agent，将结果输出到 stdout。
 * 支持两种输出格式：
 *   - text（默认）：只输出最终文本
 *   - stream-json：每个事件输出一行 JSON
 */
public class PrintMode {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 输出格式枚举
     */
    public enum OutputFormat {
        TEXT,
        STREAM_JSON
    }

    public record DurableOptions(boolean enabled, String resumeTaskId) {
        public static DurableOptions disabled() { return new DurableOptions(false, null); }
    }

    /**
     * 运行 print 模式的入口
     */
    public static void run(AppConfig config, String prompt, OutputFormat format) {
        run(config, prompt, format, DurableOptions.disabled());
    }

    public static void run(AppConfig config, String prompt, OutputFormat format,
                           DurableOptions durableOptions) {
        long startTime = System.currentTimeMillis();

        String workDir = System.getProperty("user.dir");
        ProviderConfig providerCfg = config.getProviders().get(0);
        List<McpServerConfig> mcpConfigs = config.getMcpServers() != null ? config.getMcpServers() : List.of();
        List<HookConfig> hookConfigs = config.getHooks() != null ? config.getHooks() : List.of();

        // ── 记忆管理 ──────────────────────────────────────────────────
        MemoryManager memoryManager = new MemoryManager(workDir);
        String instructionsContent = MemoryManager.loadInstructions(workDir);
        SkillCatalog skillCatalog = SkillRuntimeSupport.load(workDir);

        // ── 构建系统提示词 ──────────────────────────────────────────────
        var env = PromptBuilder.detectEnvironment(providerCfg.getModel());
        var options = new PromptBuilder.BuildOptions(skillCatalog.buildSection(workDir));
        String systemPrompt = PromptBuilder.buildSystemPrompt(env, options);

        // ── 创建 LLM 客户端 ─────────────────────────────────────────────
        LlmClient client = LlmClient.create(providerCfg, systemPrompt);
        String protocol = providerCfg.getProtocol();

        // ── 工具注册 ────────────────────────────────────────────────────
        ToolRegistry registry = ToolRegistry.createDefault();
        registry.register(new ToolSearchTool(registry, protocol));
        com.codeflow.durable.DurableTaskRepository durableStore =
                com.codeflow.AgentPlatform.registerDurableAndA2a(registry, workDir,
                        config.getA2aAgents() != null ? config.getA2aAgents() : List.of(),
                        config.getDurableStore());

        var exitPlanTool = new com.codeflow.tool.impl.ExitPlanModeTool();
        exitPlanTool.setIsPlanMode(() -> false); // print 模式不用 plan
        exitPlanTool.setPlanExists(() -> false);
        registry.register(exitPlanTool);

        // AskUser 工具：print 模式下自动返回空答案
        AskUserTool askUserTool = new AskUserTool();
        registry.register(askUserTool);

        // ── 子 Agent 工具 ───────────────────────────────────────────────
        var agentTool = new AgentTool(client, registry, protocol, providerCfg);
        agentTool.setForkDisabled(!config.isForkEnabled());
        SubAgentTaskManager subAgentTaskManager = new SubAgentTaskManager();
        agentTool.setTaskManager(subAgentTaskManager);
        registry.register(agentTool);

        // ── Worktree 工具 ───────────────────────────────────────────────
        var worktreeManager = new WorktreeManager(workDir, List.of(), 720);
        agentTool.setWorktreeManager(worktreeManager);
        String sessionId = SessionManager.newId();
        com.codeflow.durable.DurableTask durableTask = null;
        boolean resumingDurableTask = durableOptions != null && durableOptions.resumeTaskId() != null;
        if (resumingDurableTask) {
            try {
                durableTask = durableStore.get(durableOptions.resumeTaskId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "durable task not found: " + durableOptions.resumeTaskId()));
                if (durableTask.getSessionId() != null && !durableTask.getSessionId().isBlank()) {
                    sessionId = durableTask.getSessionId();
                }
            } catch (RuntimeException e) {
                System.err.println("Durable execution error: " + e.getMessage());
                return;
            }
        }
        registry.register(new com.codeflow.tool.impl.EnterWorktreeTool(worktreeManager, sessionId));
        registry.register(new com.codeflow.tool.impl.ExitWorktreeTool(worktreeManager));

        // ── 任务工具 ────────────────────────────────────────────────────
        TaskList taskList = new TaskList("default", workDir);
        registry.register(new TaskTools.TaskCreateTool(taskList));
        registry.register(new TaskTools.TaskGetTool(taskList));
        registry.register(new TaskTools.TaskListTool(taskList));
        registry.register(new TaskTools.TaskUpdateTool(taskList));

        // ── 团队工具 ────────────────────────────────────────────────────
        TeamManager teamManager = new TeamManager();
        agentTool.setTeamManager(teamManager);
        registry.register(new com.codeflow.teams.TeamTools.TeamCreateTool(teamManager));
        registry.register(new com.codeflow.teams.TeamTools.TeamDeleteTool(teamManager));
        registry.register(new com.codeflow.teams.TaskStopTool(teamManager));
        registry.register(new com.codeflow.tool.SyntheticOutputTool());
        registry.register(new com.codeflow.teams.TeamTools.SendMessageTool(teamManager, "lead"));

        // ── 权限检查器：BYPASS 模式，自动批准所有操作 ──────────────────
        PermissionChecker permChecker = new PermissionChecker(PermissionMode.BYPASS, Path.of(workDir));

        // ── 会话和文件历史 ──────────────────────────────────────────────
        FileHistory fileHistory = new FileHistory(workDir, sessionId);
        var fileStateCache = new com.codeflow.tool.FileStateCache();
        for (var tool : registry.listTools()) {
            if (tool instanceof com.codeflow.tool.impl.EditFileTool ef) {
                ef.setFileHistory(fileHistory);
                ef.setFileStateCache(fileStateCache);
            }
            if (tool instanceof com.codeflow.tool.impl.WriteFileTool wf) {
                wf.setFileHistory(fileHistory);
                wf.setFileStateCache(fileStateCache);
            }
            if (tool instanceof com.codeflow.tool.impl.ReadFileTool rf) {
                rf.setFileStateCache(fileStateCache);
            }
        }

        // ── 构建 Agent ──────────────────────────────────────────────────
        ConversationManager conversation;
        if (resumingDurableTask) {
            var saved = SessionManager.loadSession(workDir, sessionId);
            conversation = SessionManager.rebuildConversation(saved);
            String resumeMessage = "Resume durable task " + durableTask.getId()
                    + " from its persisted checkpoint. Continue from the last safe step; do not restart completed work.\n"
                    + "Persisted checkpoint: " + checkpointJson(durableTask.getCheckpoint());
            conversation.addUserMessage(resumeMessage);
            SessionManager.saveMessage(workDir, sessionId, "user", resumeMessage);
        } else {
            conversation = new ConversationManager();
        }
        Agent agent = new Agent(client, registry, protocol, providerCfg);
        agent.setFileHistory(fileHistory);
        agent.setInstructions(instructionsContent);
        agent.setChecker(permChecker);
        agent.setWorkDir(workDir);
        agent.setSessionId(sessionId);
        agent.setContextPolicy(new com.codeflow.context.ContextPolicy(config.getContextPolicy()));

        // 通知函数：排空团队邮箱和任务通知
        agent.setNotificationFn(() -> {
            var notes = new ArrayList<String>();
            notes.addAll(com.codeflow.teams.TeammateRunner.drainLeadMailbox(teamManager));
            for (var n : subAgentTaskManager.drainNotifications()) {
                notes.add("<task-notification>Task %s: %s (%s)</task-notification>"
                        .formatted(n.taskId(), n.name(), n.status()));
            }
            return notes;
        });

        // 工具名过滤器（团队协调模式）
        agent.setToolNameFilter(name -> {
            if (!config.isEnableCoordinatorMode()) return true;
            return com.codeflow.teams.Coordinator.isCoordinatorTool(name);
        });
        agent.setCoordinatorActiveFn(() ->
                com.codeflow.teams.Coordinator.isActive(config.isEnableCoordinatorMode()));

        // 子 Agent 关联
        if (registry.get("Agent") instanceof AgentTool at) {
            at.setProgressListener(progress -> {}); // print 模式不需要进度回调
        }

        // ── Hook 引擎 ──────────────────────────────────────────────────
        HookEngine hookEngine = new HookEngine();
        if (!hookConfigs.isEmpty()) {
            List<HookEngine.Hook> hooks = hookConfigs.stream().map(hc -> {
                HookEngine.EventName event = parseEventName(hc.getEvent());
                HookEngine.ActionType actionType = parseActionType(hc.getType());
                Duration timeout = hc.getTimeout() > 0
                        ? Duration.ofSeconds(hc.getTimeout()) : Duration.ZERO;
                var action = new HookEngine.Action(actionType, hc.getCommand(), hc.getMessage(),
                        hc.getUrl(), hc.getMethod(), hc.getHeaders(), hc.getBody(), timeout);
                return new HookEngine.Hook(hc.getId(), event, hc.getCondition(), action,
                        hc.isReject(), hc.isOnce(), hc.isAsync(), hc.getOnError());
            }).toList();
            hookEngine.loadHooks(hooks);
        }
        agent.setHookEngine(hookEngine);

        SkillRuntimeSupport.wire(skillCatalog, registry, () -> conversation, null,
                name -> client.setSystemPrompt(PromptBuilder.buildSystemPrompt(
                        PromptBuilder.detectEnvironment(providerCfg.getModel()),
                        new PromptBuilder.BuildOptions(skillCatalog.buildSection(workDir)))));

        // ── MCP 服务器连接 ──────────────────────────────────────────────
        String mcpInstructions = "";
        if (!mcpConfigs.isEmpty()) {
            try {
                McpManager mcpManager = new McpManager(mcpConfigs);
                var result = mcpManager.connectAll();
                for (var t : result.tools()) registry.register(t);
                for (var e : result.errors()) System.err.println("MCP error: " + e);

                if (!result.servers().isEmpty()) {
                    var mcpParts = new ArrayList<String>();
                    for (var s : result.servers()) {
                        var sb = new StringBuilder();
                        sb.append("## ").append(s.name()).append("\n");
                        if (s.instructions() != null && !s.instructions().isBlank()) {
                            sb.append(s.instructions()).append("\n");
                        }
                        var toolNames = registry.listTools().stream()
                                .filter(t -> t.name().startsWith("mcp__" + s.name() + "__"))
                                .map(com.codeflow.tool.Tool::name)
                                .toList();
                        if (!toolNames.isEmpty()) {
                            sb.append("\nAvailable tools: ").append(String.join(", ", toolNames));
                        }
                        mcpParts.add(sb.toString());
                    }
                    mcpInstructions = "# MCP Server Instructions\n\n"
                            + "The following MCP servers are connected. Use their tools when the user asks.\n\n"
                            + String.join("\n\n", mcpParts);
                }
            } catch (Exception e) {
                System.err.println("MCP init failed: " + e.getMessage());
            }
        }

        // ── 注入用户消息并启动 Agent ─────────────────────────────────────
        if (!resumingDurableTask) {
            conversation.addUserMessage(prompt);
            SessionManager.saveMessage(workDir, sessionId, "user", prompt);
        }
        if (!mcpInstructions.isEmpty()) {
            conversation.addSystemReminder(mcpInstructions);
        }

        var runtime = new AgentRuntime(agent, client, providerCfg, workDir, "print",
                memoryManager, durableStore, ignored -> { });
        boolean durableEnabled = durableOptions != null && durableOptions.enabled();
        var startOptions = new AgentRuntime.StartOptions(
                prompt, sessionId, durableEnabled,
                resumingDurableTask ? durableOptions.resumeTaskId() : null,
                "print-" + ProcessHandle.current().pid() + "-"
                        + java.util.UUID.randomUUID().toString().substring(0, 8));
        var runtimeRun = runtime.start(conversation, startOptions);
        var runHandle = runtimeRun.handle();
        if (runtimeRun.durableTaskId() != null) {
            System.err.println("Durable task: " + runtimeRun.durableTaskId());
        }
        BlockingQueue<AgentEvent> queue = runHandle.events();
        if (askUserTool != null) askUserTool.setEventQueue(queue);

        // ── 消费事件循环 ────────────────────────────────────────────────
        var resultText = new StringBuilder();
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int totalCacheReadTokens = 0;
        int totalCacheCreationTokens = 0;
        int totalTurns = 0;
        var toolCalls = new ArrayList<Map<String, Object>>();
        while (true) {
            AgentEvent event;
            try {
                event = queue.poll(120, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (event == null) {
                System.err.println("Stream timeout after 120s");
                System.exit(1);
                return;
            }

            switch (event) {
                case AgentEvent.StreamText e -> {
                    resultText.append(e.text());
                    if (format == OutputFormat.STREAM_JSON) {
                        // stream-json 模式不输出 stream_text 事件（太碎片化）
                    }
                }

                case AgentEvent.ThinkingText e -> {
                    // print 模式不输出 thinking 文本
                }

                case AgentEvent.ThinkingComplete e -> {
                    // print 模式不输出 thinking 完成
                }

                case AgentEvent.ToolUseEvent e -> {
                    if (format == OutputFormat.STREAM_JSON && e.args() != null && !e.args().isEmpty()) {
                        // 只输出带完整参数的 ToolUseEvent（即 ToolCallComplete）
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "tool_use");
                        obj.put("tool_name", e.toolName());
                        obj.put("tool_id", e.toolId());
                        obj.put("args", e.args());
                        printJson(obj);
                        toolCalls.add(Map.of("tool_name", e.toolName(), "tool_id", e.toolId()));
                    }
                }

                case AgentEvent.ToolResultEvent e -> {
                    if (format == OutputFormat.STREAM_JSON) {
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "tool_result");
                        obj.put("tool_name", e.toolName());
                        obj.put("tool_id", e.toolId());
                        obj.put("output", e.output() != null ? e.output() : "");
                        obj.put("is_error", e.isError());
                        obj.put("elapsed", e.elapsed());
                        printJson(obj);
                    }
                }

                case AgentEvent.PermissionRequestEvent e -> {
                    // BYPASS 模式下不应收到权限请求，但安全起见自动批准
                    e.future().complete(PermissionResponse.ALLOW);
                }

                case AgentEvent.AskUserRequestEvent e -> {
                    // 非交互模式自动返回空答案
                    e.future().complete(Map.of());
                }

                case AgentEvent.UsageEvent e -> {
                    totalInputTokens = e.inputTokens();
                    totalOutputTokens = e.outputTokens();
                    totalCacheReadTokens = e.cacheReadTokens();
                    totalCacheCreationTokens = e.cacheCreationTokens();
                    if (format == OutputFormat.STREAM_JSON) {
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "usage");
                        obj.put("input_tokens", e.inputTokens());
                        obj.put("output_tokens", e.outputTokens());
                        obj.put("cache_read_tokens", e.cacheReadTokens());
                        obj.put("cache_creation_tokens", e.cacheCreationTokens());
                        printJson(obj);
                    }
                }

                case AgentEvent.TurnComplete e -> {
                    totalTurns = e.turn();
                    // text 模式下清空已累积文本（中间 turn 的文本不是最终结果）
                    if (format == OutputFormat.TEXT) {
                        resultText.setLength(0);
                    }
                }

                case AgentEvent.LoopComplete e -> {
                    if (e.totalTurns() > 0) totalTurns = e.totalTurns();
                    long durationMs = System.currentTimeMillis() - startTime;

                    if (format == OutputFormat.TEXT) {
                        // 纯文本模式：输出最终结果
                        System.out.print(resultText);
                        // 确保末尾换行
                        if (resultText.length() > 0 && resultText.charAt(resultText.length() - 1) != '\n') {
                            System.out.println();
                        }
                    } else {
                        // stream-json 模式：输出最终 result 事件
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "result");
                        obj.put("result", resultText.toString());
                        obj.put("duration_ms", durationMs);
                        obj.put("num_turns", totalTurns);
                        obj.put("tool_calls", toolCalls);
                        obj.put("usage", Map.of(
                                "input_tokens", totalInputTokens,
                                "output_tokens", totalOutputTokens,
                                "cache_read_tokens", totalCacheReadTokens,
                                "cache_creation_tokens", totalCacheCreationTokens
                        ));
                        printJson(obj);
                    }
                    System.out.flush();
                    return;
                }

                case AgentEvent.ErrorEvent e -> {
                    if (format == OutputFormat.STREAM_JSON) {
                        var obj = new LinkedHashMap<String, Object>();
                        obj.put("type", "error");
                        obj.put("message", e.message());
                        printJson(obj);
                    } else {
                        System.err.println("Error: " + e.message());
                    }
                }

                case AgentEvent.CompactEvent e -> {
                    // print 模式静默处理 compact
                }

                case AgentEvent.RetryEvent e -> {
                    // print 模式静默处理 retry
                }
                case AgentEvent.CanceledEvent e -> {
                    if (format == OutputFormat.STREAM_JSON) {
                        printJson(Map.of("type", "canceled", "reason", e.reason()));
                    }
                }
            }
        }
    }

    private static String checkpointJson(Map<String, Object> checkpoint) {
        try {
            return MAPPER.writeValueAsString(checkpoint == null ? Map.of() : checkpoint);
        } catch (Exception ignored) {
            return "{}";
        }
    }

    /**
     * 将对象序列化为 JSON 并输出一行到 stdout
     */
    private static void printJson(Object obj) {
        try {
            System.out.println(MAPPER.writeValueAsString(obj));
        } catch (Exception e) {
            System.err.println("JSON serialization error: " + e.getMessage());
        }
    }

    // ── Hook 事件名 / 动作类型解析（复刻 RemoteServer） ──────────────
    private static HookEngine.EventName parseEventName(String s) {
        if (s == null) return HookEngine.EventName.SESSION_START;
        return switch (s.toLowerCase()) {
            case "session_start" -> HookEngine.EventName.SESSION_START;
            case "session_end" -> HookEngine.EventName.SESSION_END;
            case "turn_start" -> HookEngine.EventName.TURN_START;
            case "turn_end" -> HookEngine.EventName.TURN_END;
            case "pre_send" -> HookEngine.EventName.PRE_SEND;
            case "post_receive" -> HookEngine.EventName.POST_RECEIVE;
            case "pre_tool_use" -> HookEngine.EventName.PRE_TOOL_USE;
            case "post_tool_use" -> HookEngine.EventName.POST_TOOL_USE;
            case "shutdown" -> HookEngine.EventName.SHUTDOWN;
            default -> HookEngine.EventName.SESSION_START;
        };
    }

    private static HookEngine.ActionType parseActionType(String s) {
        if (s == null) return HookEngine.ActionType.COMMAND;
        return switch (s.toLowerCase()) {
            case "command" -> HookEngine.ActionType.COMMAND;
            case "prompt" -> HookEngine.ActionType.PROMPT;
            case "http" -> HookEngine.ActionType.HTTP;
            case "agent" -> HookEngine.ActionType.AGENT;
            default -> HookEngine.ActionType.COMMAND;
        };
    }
}
