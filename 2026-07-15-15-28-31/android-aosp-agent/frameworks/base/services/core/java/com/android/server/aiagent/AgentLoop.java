/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * AgentLoop — 感知-决策-行动主回环。镜像 in-app agent 的 com.fly.agent.agent.AgentLoop,
 * 从 Kotlin 移植到 Java,运行在 system_server 进程内。
 *
 * 流程:组装 messages(system + user(goal) + 感知) -> llm.decide -> 若是 finish 则结束,
 * 否则执行 tool 并把观察追加进历史 -> 循环,直到 finish 或达 maxSteps。
 */

package com.android.server.aiagent;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class AgentLoop {
    private static final int MAX_STEPS = 30;

    private final ToolRegistry registry;
    private final LlmClient llm;
    private final AgentState state;
    private final String systemPrompt;

    public AgentLoop(ToolRegistry registry, LlmClient llm, AgentState state, String systemPrompt) {
        this.registry = registry;
        this.llm = llm;
        this.state = state;
        this.systemPrompt = systemPrompt;
    }

    public void run(String goal) {
        state.reset(goal);
        List<LlmClient.LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmClient.LlmMessage("system", systemPrompt));
        messages.add(new LlmClient.LlmMessage("user", "目标: " + goal));

        try {
            while (state.running && state.step < MAX_STEPS) {
                LlmClient.Decision d = llm.decide(messages, registry.all());

                if (d.finished) {
                    state.record("finish", d.text);
                    break;
                }

                // 执行工具
                String obs;
                try {
                    obs = registry.execute(d.toolName, d.toolArgs);
                } catch (Exception e) {
                    obs = "ERROR executing " + d.toolName + ": " + e.getMessage();
                }
                state.record(d.toolName, obs);

                // finish 工具也可作为终止信号
                if ("finish".equals(d.toolName)) break;

                // 把观察喂回给 LLM
                messages.add(new LlmClient.LlmMessage("tool", "action=" + d.toolName + " -> " + obs));
            }
        } finally {
            state.running = false;
        }
    }
}
