

package com.codeflow.tool;

import java.util.Map;

public interface Tool {

    String name();

    String description();

    ToolCategory category();

    Map<String, Object> schema();

    ToolResult execute(Map<String, Object> args);

    /**
     * 工具要不要延迟加载。延迟的工具不出现在初始 tool list 里，
     * 模型得先用 ToolSearch 把 schema 捞出来才能调。
     *
     * <p>只有 MCP 工具覆盖成 {@code true}。MCP 是按项目配的，一个服务器动辄几十个工具，
     * schema 又长，全塞进初始 tool list 会把上下文占掉一大块，而且大部分工具这次会话
     * 根本用不上。内建工具是固定的那几十个，数量可控，藏起来只会让模型多绕一次
     * ToolSearch，所以一律不延迟，直接给全量 schema。
     */
    default boolean shouldDefer() {
        return false;
    }
}
