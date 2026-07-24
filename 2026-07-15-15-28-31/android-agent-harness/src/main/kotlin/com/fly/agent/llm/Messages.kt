package com.fly.agent.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 对话角色。 */
enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

/** LLM 请求的一条 tool 调用(assistant 决定要调的工具)。 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

/**
 * 统一的对话消息。
 * - assistant 消息可能携带 toolCalls
 * - tool 消息需带 toolCallId 以对应某次调用
 */
@Serializable
data class Message(
    val role: Role,
    val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
) {
    companion object {
        fun system(text: String) = Message(Role.SYSTEM, text)
        fun user(text: String) = Message(Role.USER, text)
        fun assistant(text: String, calls: List<ToolCall> = emptyList()) =
            Message(Role.ASSISTANT, text, toolCalls = calls)
        fun tool(callId: String, name: String, output: String) =
            Message(Role.TOOL, output, toolCallId = callId, name = name)
    }
}

/** 传给 LLM 的工具规格(function calling)。 */
@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/** LLM 的一次回复。 */
@Serializable
data class LlmResponse(
    val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
) {
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
}
