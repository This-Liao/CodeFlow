package com.codeflow.runtime;

import com.codeflow.conversation.ConversationManager;
import com.codeflow.skill.SkillCatalog;
import com.codeflow.skill.SkillForkHost;
import com.codeflow.tool.ToolRegistry;
import com.codeflow.tool.impl.InstallSkillTool;
import com.codeflow.tool.impl.LoadSkillTool;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Shared Skill discovery and tool wiring used by every top-level adapter. */
public final class SkillRuntimeSupport {
    private SkillRuntimeSupport() { }

    public static SkillCatalog load(String workDir) {
        return SkillCatalog.loadCatalog(workDir);
    }

    public static void wire(SkillCatalog catalog,
                            ToolRegistry registry,
                            Supplier<ConversationManager> conversation,
                            SkillForkHost forkHost,
                            Consumer<String> onInstalled) {
        var install = new InstallSkillTool();
        install.setCatalog(catalog);
        install.setOnInstalled(onInstalled);
        registry.register(install);

        var load = new LoadSkillTool();
        load.setCatalog(catalog);
        load.setForkHost(forkHost);
        load.setOnActivate((name, body) -> {
            ConversationManager active = conversation == null ? null : conversation.get();
            if (active != null) {
                active.addSystemReminder("<skill-name>" + name + "</skill-name>\n" + body);
            }
        });
        registry.register(load);
    }
}
