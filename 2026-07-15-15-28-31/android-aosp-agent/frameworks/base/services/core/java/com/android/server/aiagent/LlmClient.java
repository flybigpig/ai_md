/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * LlmClient — LLM 决策抽象。decide() 给定对话历史 + 可用工具,返回一个 Decision:
 *   - finished=true 带文本(summary)
 *   - 否则带要调用的 tool + 参数 JSON
 * 对应 in-app agent 的 com.fly.agent.llm.LlmClient(原样搬,Java 化)。
 *
 * 真实实现(OpenAI 兼容 / 端侧 NNAPI)后续接入;MVP 用 MockLlmClient 跑通链路。
 */

package com.android.server.aiagent;

import org.json.JSONObject;

import java.util.List;

public interface LlmClient {

    class LlmMessage {
        public final String role;   // "system" | "user" | "assistant" | "tool"
        public final String content;
        public LlmMessage(String role, String content) {
            this.role = role; this.content = content;
        }
    }

    class Decision {
        public boolean finished = false;
        public String text = "";
        public String toolName = "";
        public JSONObject toolArgs = new JSONObject();
    }

    Decision decide(List<LlmMessage> messages, List<Tool> tools);
}
