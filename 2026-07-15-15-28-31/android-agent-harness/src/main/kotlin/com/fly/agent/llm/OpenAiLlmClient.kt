package com.fly.agent.llm

import com.fly.agent.util.AppJson
import com.fly.agent.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * OpenAI 兼容 Chat Completions 客户端(走 function calling)。
 * 可直接指向本地 llama.cpp server(如 http://127.0.0.1:8081/v1)。
 *
 * @param baseUrl  形如 https://api.openai.com/v1 或 http://127.0.0.1:8081/v1
 * @param apiKey   Bearer token;本地服务可留空
 * @param model    模型名
 */
class OpenAiLlmClient(
    private val baseUrl: String,
    private val apiKey: String?,
    private val model: String,
) : LlmClient {

    override val label = "openai:$model@$baseUrl"

    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    override fun chat(messages: List<Message>, tools: List<ToolSpec>): LlmResponse {
        val body = buildRequest(messages, tools)
        val reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/chat/completions"))
            .timeout(Duration.ofMinutes(3))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (!apiKey.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        }

        val resp = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            throw RuntimeException("LLM 请求失败 HTTP ${resp.statusCode()}: ${resp.body()}")
        }
        return parseResponse(resp.body())
    }

    // ---------------- 请求构造 ----------------

    private fun buildRequest(messages: List<Message>, tools: List<ToolSpec>): String {
        val obj = buildJsonObject {
            put("model", model)
            put("temperature", 0.2)
            put("tool_choice", "auto")
            put("messages", buildMessages(messages))
            if (tools.isNotEmpty()) put("tools", buildTools(tools))
        }
        return AppJson.encodeToString(JsonObject.serializer(), obj)
    }

    private fun buildMessages(messages: List<Message>): JsonArray = buildJsonArray {
        messages.forEach { m ->
            add(buildJsonObject {
                when (m.role) {
                    Role.SYSTEM -> { put("role", "system"); put("content", m.content) }
                    Role.USER -> { put("role", "user"); put("content", m.content) }
                    Role.TOOL -> {
                        put("role", "tool")
                        put("tool_call_id", m.toolCallId ?: "")
                        put("content", m.content)
                    }
                    Role.ASSISTANT -> {
                        put("role", "assistant")
                        put("content", m.content)
                        if (m.toolCalls.isNotEmpty()) {
                            put("tool_calls", buildJsonArray {
                                m.toolCalls.forEach { tc ->
                                    add(buildJsonObject {
                                        put("id", tc.id)
                                        put("type", "function")
                                        put("function", buildJsonObject {
                                            put("name", tc.name)
                                            // arguments 需为 JSON 字符串
                                            put("arguments",
                                                AppJson.encodeToString(JsonObject.serializer(), tc.arguments))
                                        })
                                    })
                                }
                            })
                        }
                    }
                }
            })
        }
    }

    private fun buildTools(tools: List<ToolSpec>): JsonArray = buildJsonArray {
        tools.forEach { t ->
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", t.name)
                    put("description", t.description)
                    put("parameters", t.parameters)
                })
            })
        }
    }

    // ---------------- 响应解析 ----------------

    private fun parseResponse(raw: String): LlmResponse {
        val root = AppJson.parseToJsonElement(raw).jsonObject
        val choices = root["choices"]?.jsonArray
        if (choices.isNullOrEmpty()) {
            Log.warn("LLM 响应无 choices: $raw")
            return LlmResponse(content = "(空响应)")
        }
        val message = choices[0].jsonObject["message"]?.jsonObject
            ?: return LlmResponse(content = "(无 message)")

        val content = message["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val toolCalls = message["tool_calls"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val fn = o["function"]?.jsonObject ?: return@mapNotNull null
            val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val argsStr = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
            val argsObj = runCatching {
                AppJson.parseToJsonElement(argsStr.ifBlank { "{}" }).jsonObject
            }.getOrElse { buildJsonObject { } }
            ToolCall(id = id, name = name, arguments = argsObj)
        } ?: emptyList()

        return LlmResponse(content = content, toolCalls = toolCalls)
    }
}
