/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Tool — 设备动作抽象。execute() 返回给 LLM 的观察文本。
 * 对应 in-app agent 的 com.fly.agent.tools.Tool(原样搬,Java 化)。
 */

package com.android.server.aiagent;

import org.json.JSONObject;

public interface Tool {
    String name();
    String description();
    /** 给 LLM 的 JSON schema(参数描述)。 */
    String schema();
    /** 执行动作,返回观察文本(失败抛异常)。 */
    String execute(JSONObject args) throws Exception;
}
