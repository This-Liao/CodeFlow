// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode;

import com.mewcode.teams.TeamManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 队友进程自己组装工具集，和进程内队员那份很容易各改各的，所以这里把清单钉死：
 * 协作工具必须在，团队管理和子 Agent 必须不在。
 */
class TeammateRegistryTest {

    @TempDir
    Path tempDir;

    private String origHome;

    @BeforeEach
    void setup() {
        // 团队目录是 <home>/.mewcode/teams，把主目录指向临时目录避免污染真实配置
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
    }

    @AfterEach
    void teardown() {
        System.setProperty("user.home", origHome);
    }

    @Test
    void teammateRegistryExposesCollaborationToolsOnly() {
        var registry = MewCode.buildTeammateRegistry(
                tempDir.toString(), "anthropic", "sess-1",
                new TeamManager(), "alpha", "ann", List.of());

        // 干活的工具、通用能力，以及队友之间协作要用的消息和共享任务板
        List<String> mustHave = List.of(
                "ReadFile", "WriteFile", "EditFile", "Bash", "Glob", "Grep",
                "ToolSearch", "SyntheticOutput", "EnterWorktree", "ExitWorktree",
                "SendMessage", "TaskCreate", "TaskGet", "TaskList", "TaskUpdate");
        for (String name : mustHave) {
            assertNotNull(registry.get(name), "队友工具集缺少 " + name);
        }

        // 派人和建团队是 Lead 的职责，队友拿不到
        for (String name : List.of("Agent", "TeamCreate", "TeamDelete")) {
            assertNull(registry.get(name), "队友工具集不应包含 " + name);
        }
    }
}
