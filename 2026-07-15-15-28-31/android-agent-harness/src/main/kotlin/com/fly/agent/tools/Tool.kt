package com.fly.agent.tools

import kotlinx.serialization.json.JsonObject

/**
 * 一个可被 LLM 调用的工具(function calling 里的一个 function)。
 * 设计上与 OpenAI tools 协议对齐:name + description + JSON Schema 参数。
 */
interface Tool {
    val name: String
    val description: String

    /** JSON Schema(type=object),描述该工具的参数。 */
    val parametersSchema: JsonObject

    /**
     * 执行工具。
     * @param args LLM 给出的参数(已解析为 JsonObject)
     * @return 面向 LLM 的可读结果文本(会作为 tool 消息回喂)
     */
    fun execute(args: JsonObject): ToolResult
}

/**
 * 工具执行结果。
 * @param output   回喂给 LLM 的文本
 * @param finished 是否表示任务终结(finish 工具会置 true)
 * @param success  任务是否成功(仅在 finished 时有意义)
 */
data class ToolResult(
    val output: String,
    val finished: Boolean = false,
    val success: Boolean = false,
)
