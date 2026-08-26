// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode;

import com.mewcode.config.AppConfig;
import com.mewcode.config.ConfigLoader;
import com.mewcode.config.McpServerConfig;
import com.mewcode.config.ProviderConfig;
import com.mewcode.llm.LlmClient;
import com.mewcode.print.PrintMode;
import com.mewcode.prompt.PromptBuilder;
import com.mewcode.remote.RemoteServer;
import com.mewcode.teams.TeamManager;
import com.mewcode.teams.TeamTools;
import com.mewcode.teams.TeammateRunner;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.impl.BashTool;
import com.mewcode.tool.impl.EditFileTool;
import com.mewcode.tool.impl.GlobTool;
import com.mewcode.tool.impl.GrepTool;
import com.mewcode.tool.impl.ReadFileTool;
import com.mewcode.tool.impl.WriteFileTool;
import com.mewcode.tui.MewCodeModel;

import com.mewcode.tui.tea.Program;

import java.util.List;
import sun.misc.Signal;

public class MewCode {

    // 默认 Remote 模式监听端口
    private static final String DEFAULT_REMOTE_ADDR = ":18888";

    public static void main(String[] args) {
        // 队友进程入口：被 tmux/iTerm 拉起时命令以 --teammate 打头，走队友模式而非 TUI。
        // 格式：mewcode --teammate --team-name <t> --agent-name <n>（见 SpawnDispatcher.buildTeammateCLI）
        if (args.length > 0 && args[0].equals("--teammate")) {
            runTeammate(args);
            return;
        }

        // 解析 CLI 参数：-p "prompt"、--output-format、--remote[=addr] 和配置文件路径
        String configPath = null;
        boolean remoteMode = false;
        String remoteAddr = DEFAULT_REMOTE_ADDR;
        String printPrompt = null;
        String outputFormat = "text";
        String evalTracePath = null;
        String baselineReportPath = null;
        boolean failOnRegression = false;
        boolean durableRun = false;
        String resumeTaskId = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-p") && i + 1 < args.length) {
                printPrompt = args[++i];
            } else if (arg.startsWith("-p=")) {
                printPrompt = arg.substring("-p=".length());
            } else if (arg.equals("--output-format") && i + 1 < args.length) {
                outputFormat = args[++i];
            } else if (arg.startsWith("--output-format=")) {
                outputFormat = arg.substring("--output-format=".length());
            } else if (arg.equals("--remote")) {
                remoteMode = true;
            } else if (arg.startsWith("--remote=")) {
                remoteMode = true;
                remoteAddr = arg.substring("--remote=".length());
            } else if (arg.equals("--eval-report") && i + 1 < args.length) {
                evalTracePath = args[++i];
            } else if (arg.startsWith("--eval-report=")) {
                evalTracePath = arg.substring("--eval-report=".length());
            } else if (arg.equals("--baseline-report") && i + 1 < args.length) {
                baselineReportPath = args[++i];
            } else if (arg.startsWith("--baseline-report=")) {
                baselineReportPath = arg.substring("--baseline-report=".length());
            } else if (arg.equals("--fail-on-regression")) {
                failOnRegression = true;
            } else if (arg.equals("--durable")) {
                durableRun = true;
            } else if (arg.equals("--resume-task") && i + 1 < args.length) {
                resumeTaskId = args[++i];
                durableRun = true;
            } else if (arg.startsWith("--resume-task=")) {
                resumeTaskId = arg.substring("--resume-task=".length());
                durableRun = true;
            } else if (configPath == null) {
                configPath = arg;
            }
        }

        if (resumeTaskId != null && printPrompt == null) {
            try {
                final String requestedTaskId = resumeTaskId;
                printPrompt = new com.mewcode.durable.DurableTaskStore(System.getProperty("user.dir"))
                        .get(requestedTaskId)
                        .orElseThrow(() -> new IllegalArgumentException("durable task not found: " + requestedTaskId))
                        .getPrompt();
            } catch (RuntimeException e) {
                System.err.println("Cannot resume durable task: " + e.getMessage());
                System.exit(1);
                return;
            }
        }

        if (evalTracePath != null) {
            var output = com.mewcode.eval.EvalReportGenerator.write(
                    java.nio.file.Path.of(evalTracePath),
                    baselineReportPath == null ? null : java.nio.file.Path.of(baselineReportPath),
                    java.nio.file.Path.of(System.getProperty("user.dir"), ".codeflow", "reports", "latest"));
            System.out.println("Eval report: " + output.markdown().toAbsolutePath());
            System.out.println("Regression gate: " + (output.report().passedRegressionGate() ? "PASS" : "FAIL"));
            if (failOnRegression && !output.report().passedRegressionGate()) System.exit(2);
            return;
        }

        // 环境变量回退
        if (configPath == null) {
            String envPath = System.getenv("MEWCODE_CONFIG");
            if (envPath != null && !envPath.isBlank()) {
                configPath = envPath;
            }
        }

        AppConfig config;
        try {
            config = ConfigLoader.load(configPath);
        } catch (ConfigLoader.ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }

        // -p 模式：非交互式运行，输出结果到 stdout
        if (printPrompt != null) {
            PrintMode.OutputFormat fmt = "stream-json".equals(outputFormat)
                    ? PrintMode.OutputFormat.STREAM_JSON
                    : PrintMode.OutputFormat.TEXT;
            PrintMode.run(config, printPrompt, fmt,
                    new PrintMode.DurableOptions(durableRun, resumeTaskId));
            return;
        }

        // --remote 模式：启动 HTTP + WebSocket 服务器，不进入 TUI
        if (remoteMode) {
            var server = new RemoteServer(
                    config.getProviders(),
                    config.getMcpServers() != null ? config.getMcpServers() : List.of(),
                    config.getA2aAgents() != null ? config.getA2aAgents() : List.of(),
                    config.getContextPolicy(),
                    config.getHooks() != null ? config.getHooks() : List.of(),
                    remoteAddr,
                    config.isEnableCoordinatorMode(),
                    !config.isForkEnabled()
            );
            try {
                server.run();
            } catch (Exception e) {
                System.err.println("Remote server error: " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        // TUI 模式（默认）
        var model = new MewCodeModel(
                config.getProviders(),
                config.getMcpServers() != null ? config.getMcpServers() : List.of(),
                config.getA2aAgents() != null ? config.getA2aAgents() : List.of(),
                config.getContextPolicy(),
                config.getHooks() != null ? config.getHooks() : List.of(),
                config.isEnableCoordinatorMode(),
                !config.isForkEnabled()
        );

        var program = new Program(model);

        model.setProgram(program);

        System.out.print("\033[?25l");

        // Re-register SIGINT handler after TUI4J's program.run() starts,
        // overriding the framework's default quit-on-SIGINT behavior.
        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            try {
                Signal.handle(new Signal("INT"), sig -> model.handleSigint());
            } catch (IllegalArgumentException ignored) {}
        });

        try {
            program.run();
        } finally {
            System.out.print("\033[?25h");
            System.out.flush();
        }
    }

    /**
     * 队友工作进程入口：加载配置、建 LLM 客户端和工具白名单，接入 lead 建好的团队，
     * 然后进入 TeammateRunner 的常驻循环。
     */
    private static void runTeammate(String[] args) {
        // 解析队友专用参数：只认 --team-name / --agent-name，其余忽略
        String teamName = null;
        String memberName = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--team-name") && i + 1 < args.length) {
                teamName = args[++i];
            } else if (args[i].equals("--agent-name") && i + 1 < args.length) {
                memberName = args[++i];
            }
        }
        if (teamName == null || teamName.isEmpty() || memberName == null || memberName.isEmpty()) {
            System.err.println("--teammate requires --team-name and --agent-name");
            System.exit(1);
            return;
        }

        // 加载与 TUI 相同的配置（默认路径 / MEWCODE_CONFIG 环境变量）
        AppConfig config;
        try {
            config = ConfigLoader.load(System.getenv("MEWCODE_CONFIG"));
        } catch (ConfigLoader.ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }
        if (config.getProviders() == null || config.getProviders().isEmpty()) {
            System.err.println("no providers configured");
            System.exit(1);
            return;
        }
        ProviderConfig provider = config.getProviders().get(0);

        String workDir = System.getProperty("user.dir");
        String sessionId = java.util.UUID.randomUUID().toString();

        // Skill 目录既要进系统提示词让模型知道有哪些技能可用，也要挂到 LoadSkill
        // 工具上供模型按名字加载
        var skillCatalog = com.mewcode.skill.SkillCatalog.loadCatalog(workDir);

        // 构建系统提示词并创建 LLM 客户端
        var env = PromptBuilder.detectEnvironment(provider.getModel());
        var options = new PromptBuilder.BuildOptions(skillCatalog.buildSection(workDir));
        String systemPrompt = PromptBuilder.buildSystemPrompt(env, options);
        LlmClient client = LlmClient.create(provider, systemPrompt);
        String protocol = provider.getProtocol();

        // 队员进程直接从磁盘上的 config.json 把团队捞回来，这样它看到的队友名单
        // 和 Lead 那边是同一份。团队目录在用户主目录下，不受 worktree 换工作目录影响。
        TeamManager teamMgr = new TeamManager();
        TeamManager.Team team = teamMgr.getTeam(teamName);
        if (team == null) {
            // 配置还没落盘（例如 Lead 刚建完团队就 spawn），退化成本地构造一份，
            // 邮箱目录按同样的约定拼出来，投递仍然对得上。
            team = new TeamManager.Team(teamName, TeamManager.TeamMode.IN_PROCESS);
            teamMgr.createTeamWith(team);
        }

        ToolRegistry registry = buildTeammateRegistry(
                workDir, protocol, sessionId, teamMgr, teamName, memberName, config.getMcpServers());

        TeamManager.Member member = team.addMember(memberName, client, registry, protocol, provider);

        // Skill 工具要等成员的对话上下文建好之后再接入，激活的 SOP 直接注入这份对话。
        // 没有 ForkHost，声明 fork 模式的 skill 会退回 inline 执行
        var loadSkillTool = new com.mewcode.tool.impl.LoadSkillTool();
        loadSkillTool.setCatalog(skillCatalog);
        loadSkillTool.setOnActivate((name, body) ->
                member.conv.addSystemReminder("<skill-name>" + name + "</skill-name>\n" + body));
        registry.register(loadSkillTool);

        var installSkillTool = new com.mewcode.tool.impl.InstallSkillTool();
        installSkillTool.setCatalog(skillCatalog);
        registry.register(installSkillTool);

        // 关闭窗格 / Ctrl-C 时中断队友循环，让对话持久化等收尾逻辑得以执行
        Thread mainThread = Thread.currentThread();
        try {
            Signal.handle(new Signal("INT"), sig -> mainThread.interrupt());
        } catch (IllegalArgumentException ignored) {}
        try {
            Signal.handle(new Signal("TERM"), sig -> mainThread.interrupt());
        } catch (IllegalArgumentException ignored) {}

        String addendum = TeammateRunner.buildTeammateAddendum(teamName, memberName, null);

        // 不传初始 prompt：lead 在 spawn 前已把首个任务写入邮箱，循环首轮空闲轮询即可取到，
        // 传空可避免 runInProcessTeammate 注入重复的用户消息
        System.err.printf("[teammate %s/%s] booted, awaiting tasks%n", teamName, memberName);
        TeammateRunner.runInProcessTeammate(team, member, "", addendum);
    }

    /**
     * 组装队友工具集：文件与命令工具、工具检索、Worktree 切换、MCP 扩展，再加上
     * 团队协作工具（按自己的名字发消息，以及读写团队共享任务板）。任务板按团队名
     * 解析到同一份 tasks.json，所以队友之间看到的是同一张表。
     *
     * <p>Agent 不在其中，调用树到队友这一层为止，队友不再往下派子 Agent。
     * TeamCreate 与 TeamDelete 也不在其中，组建和解散团队是 Lead 的职责。
     *
     * <p>Skill 工具需要成员的对话上下文充当宿主，由调用方在成员建好之后单独接入。
     */
    static ToolRegistry buildTeammateRegistry(
            String workDir,
            String protocol,
            String sessionId,
            TeamManager teamMgr,
            String teamName,
            String memberName,
            List<McpServerConfig> mcpConfigs) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new EditFileTool());
        registry.register(new BashTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());

        registry.register(new com.mewcode.tool.impl.ToolSearchTool(registry, protocol));
        registry.register(new com.mewcode.tool.SyntheticOutputTool());

        var worktreeManager = new com.mewcode.worktree.WorktreeManager(workDir, List.of(), 720);
        registry.register(new com.mewcode.tool.impl.EnterWorktreeTool(worktreeManager, sessionId));
        registry.register(new com.mewcode.tool.impl.ExitWorktreeTool(worktreeManager));

        registry.register(new TeamTools.SendMessageTool(teamMgr, memberName));
        registry.register(new com.mewcode.teams.TeamTaskTools.TaskCreateTool(teamMgr, teamName, memberName));
        registry.register(new com.mewcode.teams.TeamTaskTools.TaskGetTool(teamMgr, teamName));
        registry.register(new com.mewcode.teams.TeamTaskTools.TaskListTool(teamMgr, teamName));
        registry.register(new com.mewcode.teams.TeamTaskTools.TaskUpdateTool(teamMgr, teamName));

        if (mcpConfigs != null && !mcpConfigs.isEmpty()) {
            try {
                var result = new com.mewcode.mcp.McpManager(mcpConfigs).connectAll();
                for (var t : result.tools()) registry.register(t);
                for (var e : result.errors()) System.err.println("MCP error: " + e);
            } catch (Exception e) {
                System.err.println("MCP setup failed: " + e.getMessage());
            }
        }

        return registry;
    }
}
