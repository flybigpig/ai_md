package com.fly.agent.llm

import com.fly.agent.util.AgentLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * 无需真实模型的 Mock 决策器，用于在没有 LLM 时验证整条回环。
 *
 * 它并非死脚本，而是做了“启发式 grounding”：
 * - 解析 get_ui 返回的可交互节点文本；
 * - 用目标里的中文关键词与节点 label 做字符重叠匹配，返回 tap_index；
 * - 目标含 App 名时先 open_app；
 * - 找不到匹配或步数耗尽时 finish。
 * 因此它能真实演示“感知 → 决策 → 动作”，只是决策是规则而非模型。
 */
class MockLlmClient(private val goal: String) : LlmClient {

    override val id: String = "mock"

    private var step = 0
    private var opened = false
    private val clicked = mutableSetOf<Int>()
    private val knownApps = mapOf(
        "设置" to "设置", "微信" to "微信", "相机" to "相机",
        "浏览器" to "浏览器", "文件" to "文件管理", "计算器" to "计算器",
        "日历" to "日历", "联系人" to "联系人", "相册" to "图库", "时钟" to "时钟"
    )

    override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDef>): ChatMessage {
        step++
        AgentLog.d("[Mock] 决策第 $step 步")

        // 1) 目标指向某 App 且尚未打开 → 先启动
        val appWord = detectApp(goal)
        if (appWord != null && !opened) {
            opened = true
            AgentLog.d("[Mock] 决定 open_app($appWord)")
            return toolCall("open_app", mapOf("spec" to JsonPrimitive(appWord)))
        }

        // 2) 找最近一次 get_ui 的 dump
        val dump = messages.lastOrNull { it.content?.contains("可交互元素") == true }?.content
        if (dump == null) {
            AgentLog.d("[Mock] 决定 get_ui")
            return toolCall("get_ui", emptyMap())
        }

        // 3) 解析节点并匹配
        val nodes = parseNodes(dump)
        val target = pickTarget(nodes, goal)
        if (target != null) {
            clicked.add(target.first)
            AgentLog.d("[Mock] 决定 tap_index(${target.first}) label=${target.second}")
            return toolCall("tap_index", mapOf("index" to JsonPrimitive(target.first)))
        }

        // 4) 没啥可点 → 结束
        AgentLog.d("[Mock] 无匹配，决定 finish")
        return toolCall("finish", mapOf("summary" to JsonPrimitive("Mock 完成：未找到匹配目标的节点。")))
    }

    private fun detectApp(goal: String): String? {
        for ((kw, app) in knownApps) {
            if (goal.contains(kw)) return app
        }
        val verbs = listOf("打开", "启动", "进入", "开启")
        for (v in verbs) {
            val i = goal.indexOf(v)
            if (i >= 0 && i + v.length < goal.length) {
                return goal.substring(i + v.length).take(4)
            }
        }
        return null
    }

    private fun parseNodes(dump: String): List<Pair<Int, String>> {
        val re = Regex("^\\[(\\d+)\\]\\s*<[^>]*>\\s*\"([^\"]*)\"", RegexOption.MULTILINE)
        return re.findAll(dump).map { m ->
            m.groupValues[1].toInt() to m.groupValues[2]
        }.toList()
    }

    private fun pickTarget(nodes: List<Pair<Int, String>>, goal: String): Pair<Int, String>? {
        val goalChars = goal.filter { it.isLetterOrDigit() || it.isHighSurrogate() }
        var best: Pair<Int, String>? = null
        var bestScore = 1 // 至少重叠 1 个字符才算匹配
        for ((idx, label) in nodes) {
            if (idx in clicked) continue
            val score = label.count { it in goalChars }
            if (score > bestScore) {
                bestScore = score
                best = idx to label
            }
        }
        return best
    }

    private fun toolCall(name: String, args: Map<String, JsonPrimitive>): ChatMessage {
        val obj: JsonObject = buildJsonObject {
            args.forEach { (k, v) -> put(k, v) }
        }
        return ChatMessage(
            role = "assistant",
            tool_calls = listOf(
                ToolCall(id = "call_$step", function = ToolCallFn(name, obj.toString()))
            )
        )
    }
}
