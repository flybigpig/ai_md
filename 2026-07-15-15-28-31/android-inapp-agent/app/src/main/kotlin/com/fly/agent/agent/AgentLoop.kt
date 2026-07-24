package com.fly.agent.agent

import com.fly.agent.llm.ChatMessage
import com.fly.agent.llm.LlmClient
import com.fly.agent.tools.ToolRegistry
import com.fly.agent.util.AgentLog
import com.fly.agent.util.Json
import kotlinx.serialization.json.JsonObject as SerJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 感知-决策-行动 回环：
 *   观察(get_ui/截图) → LLM 决策 → 执行工具 → 把观察回灌 → 再决策 …
 *
 * 两种决策来源都兼容：
 *  - 标准 tool_calling：assistant 消息带 tool_calls；
 *  - 兜底协议：本地量化模型常不支持 tool_calling，此时从 content 文本里
 *    解析 `{"tool":"...","args":{...}}` 形式的 JSON 动作。
 */
class AgentLoop(
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val goal: String,
    private val maxSteps: Int = 30
) {
    suspend fun run() {
        val messages = mutableListOf(
            ChatMessage(role = "system", content = Prompts.system(goal))
        )
        var steps = 0
        while (steps < maxSteps) {
            steps++
            AgentLog.i("=== Step $steps ===")
            val assistant = try {
                llm.chat(messages, tools.defs())
            } catch (t: Throwable) {
                AgentLog.e("LLM 调用失败", t)
                return
            }
            messages.add(assistant)

            val calls = assistant.tool_calls
            if (!calls.isNullOrEmpty()) {
                var finished = false
                for (call in calls) {
                    AgentLog.i("→ ${call.function.name}(${call.function.arguments})")
                    val obs = tools.execute(call.function.name, call.function.arguments)
                    messages.add(
                        ChatMessage(
                            role = "tool",
                            toolCallId = call.id,
                            name = call.function.name,
                            content = obs
                        )
                    )
                    if (tools.get(call.function.name)?.isFinish == true) finished = true
                }
                if (finished) {
                    AgentLog.i("finish 触发，回环结束")
                    return
                }
            } else {
                // 兜底：从文本解析 JSON 动作
                val action = tryParseAction(assistant.content)
                if (action != null) {
                    AgentLog.i("→(fallback) ${action.first}(${action.second})")
                    val obs = tools.execute(action.first, action.second)
                    messages.add(
                        ChatMessage(
                            role = "tool",
                            toolCallId = "fallback",
                            name = action.first,
                            content = obs
                        )
                    )
                    if (tools.get(action.first)?.isFinish == true) {
                        AgentLog.i("finish 触发，回环结束")
                        return
                    }
                } else {
                    AgentLog.i("模型返回文本结论，结束：${assistant.content?.take(200)}")
                    return
                }
            }
        }
        AgentLog.i("达到最大步数 $maxSteps，强制结束")
    }

    /**
     * 解析形如 `{"tool":"tap_index","args":{"index":3}}` 的文本动作。
     * 兼容 "tool"/"name" 与 "args"/"arguments" 两种键名。
     */
    private fun tryParseAction(content: String?): Pair<String, String>? {
        if (content.isNullOrBlank()) return null
        val s = content.trim()
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val jsonStr = s.substring(start, end + 1)
        return try {
            val obj = Json.parseToJsonElement(jsonStr)
            if (obj is SerJsonObject) {
                val name = (obj["tool"] ?: obj["name"])?.let {
                    (it as? JsonPrimitive)?.content
                } ?: return null
                val argsEl = obj["args"] ?: obj["arguments"] ?: buildJsonObject { }
                val argsJson = if (argsEl is SerJsonObject)
                    Json.encodeToString(SerJsonObject.serializer(), argsEl)
                else argsEl.toString()
                name to argsJson
            } else null
        } catch (t: Throwable) {
            null
        }
    }
}
