package com.fly.agent.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * OpenAI / 兼容端点的 chat-completions 协议模型。
 * arguments 字段是 JSON 字符串（protocol 约定），由 agent 层再解析。
 */

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val tool_calls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFn
)

@Serializable
data class ToolCallFn(
    val name: String,
    val arguments: String  // JSON 字符串
)

@Serializable
data class FunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class ToolDef(
    val type: String = "function",
    val function: FunctionDef
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDef>? = null,
    @SerialName("tool_choice") val toolChoice: String? = "auto",
    val temperature: Double = 0.2,
    @SerialName("max_tokens") val maxTokens: Int? = 1024
)

@Serializable
data class ChatResponse(
    val choices: List<Choice>,
    val error: ChatError? = null
)

@Serializable
data class Choice(
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class ChatError(
    val message: String? = null,
    val type: String? = null
)
