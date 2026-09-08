package com.codeflow.agent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class AgentRunHandleTest {

    @Test
    void cancelInterruptsOwningWorkerAndEmitsOneEvent() throws Exception {
        var queue = new LinkedBlockingQueue<AgentEvent>();
        var observed = new AtomicBoolean();
        var handle = new AgentRunHandle(queue, event -> observed.set(true));
        Thread worker = Thread.ofVirtual().unstarted(() -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        handle.attach(worker);
        worker.start();

        assertTrue(handle.cancel("测试取消"));
        assertFalse(handle.cancel("重复取消"));
        worker.join(2_000);

        AgentEvent event = queue.poll(1, TimeUnit.SECONDS);
        assertInstanceOf(AgentEvent.CanceledEvent.class, event);
        assertEquals("测试取消", ((AgentEvent.CanceledEvent) event).reason());
        assertTrue(observed.get());
        assertFalse(handle.isAlive());
        assertNull(queue.poll());
    }
}
