package com.codeflow;

import com.codeflow.a2a.A2aDelegateTool;
import com.codeflow.config.A2aAgentConfig;
import com.codeflow.config.DurableStoreConfig;
import com.codeflow.durable.DurableRepositoryFactory;
import com.codeflow.durable.DurableTaskRepository;
import com.codeflow.durable.DurableTaskTool;
import com.codeflow.tool.ToolRegistry;

import java.util.List;

/** Shared registration for long-horizon and interoperable agent infrastructure. */
public final class AgentPlatform {
    private AgentPlatform() {}

    public static DurableTaskRepository registerDurableAndA2a(
            ToolRegistry registry, String workDir, List<A2aAgentConfig> a2aAgents) {
        return registerDurableAndA2a(registry, workDir, a2aAgents, null);
    }

    public static DurableTaskRepository registerDurableAndA2a(
            ToolRegistry registry, String workDir, List<A2aAgentConfig> a2aAgents,
            DurableStoreConfig durableConfig) {
        var store = DurableRepositoryFactory.create(workDir, durableConfig);
        registry.register(new DurableTaskTool(store));
        if (a2aAgents != null && a2aAgents.stream().anyMatch(A2aAgentConfig::isEnabled)) {
            registry.register(new A2aDelegateTool(a2aAgents, store));
        }
        return store;
    }
}
