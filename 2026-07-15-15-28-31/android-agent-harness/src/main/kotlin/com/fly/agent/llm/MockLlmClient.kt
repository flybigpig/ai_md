package com.fly.agent.llm

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

/**
 * 不接真实模型的 Mock 客户端,用于先把「感知-行动」回环跑绿。
 *
 * 两种用法:
 * 1) 传入固定脚本 [script],逐条回放(适合单元测试,完全确定)。
 * 2) 不传脚本时走内置启发式:先 get_ui 看一眼界面,再直接 finish 成功。
 *    足以验证整条链路(adb dump -> 解析 -> 工具执行 -> 回喂 -> 终结)。
 */
class MockLlmClient(
    private val script: List<LlmResponse> = emptyList(),
) : LlmClient {

    override val label = "mock"
    private val cursor = AtomicInteger(0)

    override fun chat(messages: List<Message>, tools: List<ToolSpec>): LlmResponse {
        // 有脚本:按序回放,放完就 finish。
        if (script.isNotEmpty()) {
            val i = cursor.getAndIncrement()
            return script.getOrElse(i) { finishResponse("脚本执行完毕") }
        }

        // 内置启发式:根据已产生的工具调用次数推进。
        val toolMsgCount = messages.count { it.role == Role.TOOL }
        return when (toolMsgCount) {
            0 -> call("get_ui", buildJsonObject { })
            else -> finishResponse("Mock 回环验证完成:已成功获取界面并结束。")
        }
    }

    private fun call(name: String, args: kotlinx.serialization.json.JsonObject) =
        LlmResponse(
            content = "(mock) 调用 $name",
            toolCalls = listOf(ToolCall(id = "mock_${cursor.get()}", name = name, arguments = args)),
        )

    private fun finishResponse(summary: String) =
        LlmResponse(
            content = "(mock) 结束",
            toolCalls = listOf(
                ToolCall(
                    id = "mock_finish",
                    name = "finish",
                    arguments = buildJsonObject {
                        put("success", true)
                        put("summary", summary)
                    },
                ),
            ),
        )
}
