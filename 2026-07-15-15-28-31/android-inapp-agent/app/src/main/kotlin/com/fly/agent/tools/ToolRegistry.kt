package com.fly.agent.tools

import com.fly.agent.llm.ToolDef
import com.fly.agent.util.AgentLog
import kotlinx.serialization.json.JsonObject

/**
 * 工具注册表：把一组 Tool 暴露给 LLM（schema），并按名分发执行。
 */
class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }

    fun defs(): List<ToolDef> = tools.map { it.schema() }

    fun names(): List<String> = tools.map { it.name }

    fun get(name: String): Tool? = byName[name]

    /**
     * 执行一个工具调用。argsJson 是 tool_call.arguments 字符串。
     * 返回观察结果文本；若工具不存在或参数非法，返回错误文本（不抛异常，避免回环中断）。
     */
    suspend fun execute(name: String, argsJson: String): String {
        val tool = byName[name]
        if (tool == null) {
            AgentLog.w("未知工具: $name")
            return "错误：未知工具 $name"
        }
        return try {
            val args = if (argsJson.isBlank()) emptyMap()
            else com.fly.agent.util.Json.decodeFromString(JsonObject.serializer(), argsJson)
                .mapValues { it.value }
            tool.run(args)
        } catch (t: Throwable) {
            AgentLog.e("工具 $name 执行异常", t)
            "错误：工具 $name 执行失败 -> ${t.message}"
        }
    }
}
