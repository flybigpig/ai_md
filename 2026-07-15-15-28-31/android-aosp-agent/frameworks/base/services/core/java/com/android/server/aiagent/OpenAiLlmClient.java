/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * OpenAiLlmClient — 真实 LLM 接入(OpenAI 兼容 /v1/chat/completions)。
 *
 * 设计取舍:跑在 system_server 内,用 boot classpath 自带的
 *   - java.net.HttpURLConnection (HTTP,零新依赖,不拖重 framework 构建)
 *   - org.json (JSON,框架自带)
 * 不走 OkHttp/Retrofit,避免给 services 模块加第三方库 + SELinux/neverallow 风险。
 *
 * 协议兼容两层:
 *   1) tool calling:响应 message.tool_calls[].function.{name,arguments}
 *   2) JSON 兜底:本地小模型(如 llama.cpp :8081)tool calling 不稳时,
 *      从 content 里抠 JSON {"tool":...,"args":{...}} 或 {"action":...,...}
 *
 * 注意:system_server 出网需 SELinux 放行(见 README "LLM 接入"段)。
 */

package com.android.server.aiagent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class OpenAiLlmClient implements LlmClient {

    private final String baseUrl;
    private final String model;
    private final int connectTimeoutMs = 10_000;
    private final int readTimeoutMs = 60_000;

    public OpenAiLlmClient(String baseUrl, String model) {
        String u = baseUrl;
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        this.baseUrl = u;
        this.model = (model == null || model.isEmpty()) ? "local-model" : model;
    }

    @Override
    public Decision decide(List<LlmMessage> messages, List<Tool> tools) {
        Decision d = new Decision();
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", buildMessages(messages));
            body.put("tools", buildTools(tools));
            body.put("tool_choice", "auto");

            String resp = post(baseUrl + "/chat/completions", body.toString());
            parseResponse(resp, d);
        } catch (Exception e) {
            // 网络/解析失败时优雅降级:结束回环并报告错误,避免死循环
            d.finished = true;
            d.text = "LLM error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return d;
    }

    // ====================== 请求体构造 ======================

    private JSONArray buildMessages(List<LlmMessage> messages) {
        JSONArray arr = new JSONArray();
        for (LlmMessage m : messages) {
            JSONObject o = new JSONObject();
            o.put("role", m.role);
            o.put("content", m.content == null ? "" : m.content);
            arr.put(o);
        }
        return arr;
    }

    private JSONArray buildTools(List<Tool> tools) {
        JSONArray arr = new JSONArray();
        for (Tool t : tools) {
            JSONObject fn = new JSONObject();
            fn.put("name", t.name());
            fn.put("description", t.description());
            // schema() 已是合法 JSON 串,直接解析嵌入
            try {
                fn.put("parameters", new JSONObject(t.schema()));
            } catch (Exception e) {
                fn.put("parameters", new JSONObject("{\"type\":\"object\",\"properties\":{}}"));
            }
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("function", fn);
            arr.put(tool);
        }
        return arr;
    }

    // ====================== 响应解析 ======================

    private void parseResponse(String resp, Decision d) throws Exception {
        JSONObject root = new JSONObject(resp);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            d.finished = true;
            d.text = "empty choices";
            return;
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");

        // 1) 优先 tool_calls
        JSONArray toolCalls = message.optJSONArray("tool_calls");
        if (toolCalls != null && toolCalls.length() > 0) {
            JSONObject tc = toolCalls.getJSONObject(0).getJSONObject("function");
            d.toolName = tc.optString("name", "");
            String argsStr = tc.optString("arguments", "{}");
            try {
                d.toolArgs = new JSONObject(argsStr);
            } catch (Exception e) {
                d.toolArgs = new JSONObject();
            }
            return;
        }

        // 2) JSON 兜底:从 content 抠动作
        String content = message.optString("content", "");
        parseContentFallback(content, d);
    }

    private void parseContentFallback(String content, Decision d) {
        if (content == null || content.isEmpty()) {
            d.finished = true;
            d.text = "empty content";
            return;
        }
        // 去 ```json 围栏
        String c = content.trim();
        if (c.startsWith("```")) {
            int first = c.indexOf('{');
            int last = c.lastIndexOf('}');
            if (first >= 0 && last > first) c = c.substring(first, last + 1);
        }
        try {
            JSONObject jo = new JSONObject(c);
            String name = jo.optString("tool", jo.optString("action", jo.optString("name", "")));
            if (!name.isEmpty()) {
                d.toolName = name;
                Object a = jo.opt("args");
                if (a instanceof JSONObject) d.toolArgs = (JSONObject) a;
                else if (a instanceof String) {
                    try { d.toolArgs = new JSONObject((String) a); }
                    catch (Exception e) { d.toolArgs = new JSONObject(); }
                } else d.toolArgs = new JSONObject();
                return;
            }
            if (jo.optBoolean("finish", jo.optBoolean("done", false))) {
                d.finished = true;
                d.text = jo.optString("summary", jo.optString("text", "done"));
                return;
            }
        } catch (Exception ignored) {
            // 不是 JSON → 当作自然语言思考,结束并回显
        }
        d.finished = true;
        d.text = content;
    }

    // ====================== HTTP ======================

    private String post(String urlStr, String body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        // 本地端点一般无鉴权;若云端端点需要,这里加 Bearer:
        // conn.setRequestProperty("Authorization", "Bearer " + apiKey);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        if (code < 200 || code >= 300) {
            throw new RuntimeException("HTTP " + code + ": " + sb);
        }
        return sb.toString();
    }
}
