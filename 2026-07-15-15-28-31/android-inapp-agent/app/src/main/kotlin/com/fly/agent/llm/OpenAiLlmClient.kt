package com.fly.agent.llm

import com.fly.agent.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 直连 OpenAI 兼容端点（/v1/chat/completions），支持 tool calling。
 * 默认指向本地 8081（通过 `adb reverse tcp:8081 tcp:8081` 把设备 127.0.0.1:8081 映射到主机）。
 *
 * 注意：很多量化本地模型的 tool calling 不稳定。本客户端只负责协议，
 * 若返回的是纯文本而非 tool_calls，由 AgentLoop 走“文本里解析 JSON 动作”的兜底协议。
 */
class OpenAiLlmClient(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String = "n/a",
    private val temperature: Double = 0.2,
    private val maxTokens: Int = 1024
) : LlmClient {

    override val id: String = "openai($model@$baseUrl)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json".toMediaType()

    override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDef>): ChatMessage {
        return withContext(Dispatchers.IO) {
            val reqBody = ChatRequest(
                model = model,
                messages = messages,
                tools = tools.ifEmpty { null },
                toolChoice = if (tools.isEmpty()) null else "auto",
                temperature = temperature,
                maxTokens = maxTokens
            )
            val json = Json.encodeToString(ChatRequest.serializer(), reqBody)
            val httpReq = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(json.toRequestBody(mediaType))
                .build()

            val resp = client.newCall(httpReq).execute()
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("LLM HTTP ${resp.code}: ${body.take(500)}")
            }
            val parsed = Json.decodeFromString(ChatResponse.serializer(), body)
            parsed.error?.let {
                throw RuntimeException("LLM error: ${it.message ?: it.type}")
            }
            parsed.choices.firstOrNull()?.message
                ?: throw RuntimeException("LLM 返回空 choices: ${body.take(300)}")
        }
    }
}
