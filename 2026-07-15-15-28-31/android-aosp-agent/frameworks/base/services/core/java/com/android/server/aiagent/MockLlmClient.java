/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * MockLlmClient — 不联网的脚本化决策器。用于先把回环 + 特权工具链路跑绿,
 * 验证 AIAgentManagerService 的"手"是否工作。逻辑:
 *   step 1: 调 get_ui 看界面
 *   step 2: 调 finish 结束
 * 真实接入 OpenAI / 端侧 NNAPI 后替换本类即可。
 */

package com.android.server.aiagent;

import org.json.JSONObject;

import java.util.List;

public final class MockLlmClient implements LlmClient {

    private int callCount = 0;

    @Override
    public Decision decide(List<LlmMessage> messages, List<Tool> tools) {
        Decision d = new Decision();
        callCount++;
        if (callCount == 1) {
            d.toolName = "get_ui";
            d.toolArgs = new JSONObject();
        } else {
            d.finished = true;
            d.text = "已查看当前界面(第 " + callCount + " 步)。Mock 回环完成。";
        }
        return d;
    }

    public void reset() { callCount = 0; }
}
