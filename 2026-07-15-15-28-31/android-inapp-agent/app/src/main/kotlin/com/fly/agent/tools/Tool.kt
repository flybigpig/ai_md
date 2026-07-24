package com.fly.agent.tools

import com.fly.agent.llm.ToolDef
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一个可被 LLM 调用的工具。
 * run() 返回“观察结果”文本，作为 tool 消息回灌给模型。
 */
interface Tool {
    val name: String
    val description: String

    /** 生成 OpenAI function-calling 的 schema */
    fun schema(): ToolDef

    /** 执行。args 由 AgentLoop 把 tool_call.arguments(JSON 串) 解析成 Map 后传入 */
    suspend fun run(args: Map<String, JsonElement>): String

    /** 是否为结束工具（AgentLoop 据此终止回环） */
    val isFinish: Boolean get() = false
}

abstract class BaseTool(
    override val name: String,
    override val description: String
) : Tool {

    protected fun str(args: Map<String, JsonElement>, key: String, default: String? = null): String? {
        val v = args[key] ?: return default
        return v.jsonPrimitive.content
    }

    protected fun int(args: Map<String, JsonElement>, key: String, default: Int): Int {
        val v = args[key] ?: return default
        return v.jsonPrimitive.content.toIntOrNull() ?: default
    }

    protected fun bool(args: Map<String, JsonElement>, key: String, default: Boolean): Boolean {
        val v = args[key] ?: return default
        return v.jsonPrimitive.content.toBooleanStrictOrNull() ?: default
    }
}
