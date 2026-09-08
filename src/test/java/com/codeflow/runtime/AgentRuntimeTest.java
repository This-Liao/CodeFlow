package com.codeflow.runtime;

import com.codeflow.agent.Agent;
import com.codeflow.agent.AgentEvent;
import com.codeflow.config.ProviderConfig;
import com.codeflow.conversation.ConversationManager;
import com.codeflow.durable.DurableTaskState;
import com.codeflow.durable.DurableTaskStore;
import com.codeflow.llm.LlmClient;
import com.codeflow.llm.StreamEvent;
import com.codeflow.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeTest {
    @TempDir Path dir;

    @Test
    void oneRunPersistsTraceAndCompletesDurableTask() throws Exception {
        var client = new CompletingClient();
        var config = provider();
        var agent = new Agent(client, new ToolRegistry(), "openai-compat", config);
        agent.setWorkDir(dir.toString());
        agent.setSessionId("session-1");
        var store = new DurableTaskStore(dir.toString());
        var runtime = new AgentRuntime(agent, client, config, dir.toString(), "test",
                null, store, ignored -> { });
        var conversation = new ConversationManager();
        conversation.addUserMessage("修复测试");

        var run = runtime.start(conversation,
                AgentRuntime.StartOptions.interactive("修复测试", "session-1", "test"));
        awaitLoopComplete(run.handle().events());

        var task = store.get(run.durableTaskId()).orElseThrow();
        assertEquals(DurableTaskState.COMPLETED, task.getState());
        assertTrue(run.trace().current().isSuccess());
        assertTrue(Files.isRegularFile(dir.resolve(".codeflow/traces")
                .resolve(run.trace().current().getRunId() + ".json")));
    }

    @Test
    void cancelPersistsCanceledTaskAndTrace() throws Exception {
        var client = new WaitingClient();
        var config = provider();
        var agent = new Agent(client, new ToolRegistry(), "openai-compat", config);
        var store = new DurableTaskStore(dir.toString());
        var runtime = new AgentRuntime(agent, client, config, dir.toString(), "remote",
                null, store, ignored -> { });
        var conversation = new ConversationManager();
        conversation.addUserMessage("长任务");

        var run = runtime.start(conversation,
                AgentRuntime.StartOptions.interactive("长任务", "session-2", "remote"));
        assertTrue(run.handle().cancel("浏览器停止"));
        awaitLoopComplete(run.handle().events());

        assertEquals(DurableTaskState.CANCELED,
                store.get(run.durableTaskId()).orElseThrow().getState());
        assertEquals(Boolean.TRUE, run.trace().current().getMetadata().get("canceled"));
        assertFalse(run.trace().current().isSuccess());
    }

    private static void awaitLoopComplete(BlockingQueue<AgentEvent> events) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            AgentEvent event = events.poll(200, TimeUnit.MILLISECONDS);
            if (event instanceof AgentEvent.LoopComplete) return;
        }
        fail("Agent loop did not complete");
    }

    private static ProviderConfig provider() {
        var config = new ProviderConfig();
        config.setProtocol("openai-compat");
        config.setModel("test-model");
        config.setContextWindow(16_000);
        config.setMaxOutputTokens(1_000);
        return config;
    }

    private static final class CompletingClient implements LlmClient {
        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv,
                                                  List<Map<String, Object>> tools) {
            var events = new LinkedBlockingQueue<StreamEvent>();
            events.add(new StreamEvent.TextDelta("完成"));
            events.add(new StreamEvent.StreamEnd("end_turn", 10, 2));
            return events;
        }

        @Override public void setSystemPrompt(String prompt) { }
    }

    private static final class WaitingClient implements LlmClient {
        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv,
                                                  List<Map<String, Object>> tools) {
            return new LinkedBlockingQueue<>();
        }

        @Override public void setSystemPrompt(String prompt) { }
    }
}
