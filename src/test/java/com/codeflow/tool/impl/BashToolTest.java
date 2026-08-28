package com.codeflow.tool.impl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashToolTest {

    @Test
    void usesCmdWithoutWslOnWindows() {
        assertEquals(
                List.of("cmd.exe", "/d", "/s", "/c", "java -version"),
                BashTool.shellCommand("java -version", "Windows 11"));
    }

    @Test
    void usesBashOnUnixLikeSystems() {
        assertEquals(
                List.of("bash", "-c", "java -version"),
                BashTool.shellCommand("java -version", "Linux"));
    }

    @Test
    void executesThroughTheCurrentPlatformShell() {
        var result = new BashTool().execute(Map.of("command", "java -version", "timeout", 30));

        assertFalse(result.isError(), result.output());
        assertTrue(result.output().toLowerCase().contains("version"), result.output());
    }
}
