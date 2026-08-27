package com.codeflow.a2a;

import java.net.URI;
import java.util.List;

/** Validated subset of an A2A 1.0 Agent Card used by the host. */
public record A2aAgentCard(
        String name,
        String description,
        String version,
        List<Interface> supportedInterfaces,
        List<Skill> skills
) {
    public record Interface(URI url, String protocolBinding, String protocolVersion) {}
    public record Skill(String id, String name, String description, List<String> tags) {}
}
