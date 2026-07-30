/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * ToolRegistry — 工具注册表。AgentLoop 经它查工具、执行、并产出给 LLM 的 tool 列表。
 * 对应 in-app agent 的 com.fly.agent.tools.ToolRegistry。
 */

package com.android.server.aiagent;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool t) {
        tools.put(t.name(), t);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public List<Tool> all() {
        return new ArrayList<>(tools.values());
    }

    public String execute(String name, JSONObject args) throws Exception {
        Tool t = tools.get(name);
        if (t == null) throw new IllegalArgumentException("unknown tool: " + name);
        return t.execute(args);
    }

    /** 把注册的工具序列化为 OpenAI tool-calling 格式(供 LLM 客户端使用)。 */
    public String toLlmToolsJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (Tool t : tools.values()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"type\":\"function\",\"function\":{")
               .append("\"name\":\"").append(t.name()).append("\"")
               .append(",\"description\":\"").append(t.description().replace("\"", "\\\"")).append("\"")
               .append(",\"parameters\":").append(t.schema())
               .append("}}");
        }
        sb.append("]");
        return sb.toString();
    }
}
