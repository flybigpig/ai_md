package com.fly.agent.tools

import com.fly.agent.llm.ToolSpec
import kotlinx.serialization.json.JsonObject

/** 工具注册表:统一管理所有工具,并能导出给 LLM 的 tool 规格列表。 */
class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    fun register(tool: Tool): ToolRegistry {
        tools[tool.name] = tool
        return this
    }

    fun get(name: String): Tool? = tools[name]

    fun all(): List<Tool> = tools.values.toList()

    /** 导出为 LLM function-calling 需要的规格。 */
    fun toSpecs(): List<ToolSpec> = tools.values.map { t ->
        ToolSpec(name = t.name, description = t.description, parameters = t.parametersSchema)
    }

    fun invoke(name: String, args: JsonObject): ToolResult {
        val tool = tools[name]
            ?: return ToolResult("错误:不存在名为 '$name' 的工具。可用工具: ${tools.keys.joinToString()}")
        return runCatching { tool.execute(args) }
            .getOrElse { e -> ToolResult("工具 '$name' 执行异常: ${e.message}") }
    }
}
