package com.fly.agent

import com.fly.agent.agent.AgentLoop
import com.fly.agent.llm.LlmResponse
import com.fly.agent.llm.MockLlmClient
import com.fly.agent.llm.ToolCall
import com.fly.agent.tools.Tool
import com.fly.agent.tools.ToolRegistry
import com.fly.agent.tools.ToolResult
import com.fly.agent.tools.emptySchema
import com.fly.agent.tools.schemaOf
import com.fly.agent.tools.Prop
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 不依赖真机:用两个内存工具(echo + finish)验证 AgentLoop 能正确
 * 「调用工具 -> 回喂结果 -> 依据脚本终结」。
 */
class AgentLoopTest {

    private class EchoTool : Tool {
        var called = 0
        override val name = "echo"
        override val description = "回显文本"
        override val parametersSchema = schemaOf(Prop("text", "string", "内容"))
        override fun execute(args: JsonObject): ToolResult {
            called++
            return ToolResult("echoed")
        }
    }

    private class FinishTool : Tool {
        override val name = "finish"
        override val description = "结束"
        override val parametersSchema = emptySchema()
        override fun execute(args: JsonObject): ToolResult =
            ToolResult("done", finished = true, success = true)
    }

    @Test
    fun `loop executes tool then finishes per script`() {
        val echo = EchoTool()
        val registry = ToolRegistry()
            .register(echo)
            .register(FinishTool())

        val script = listOf(
            LlmResponse(
                content = "先 echo",
                toolCalls = listOf(
                    ToolCall("c1", "echo", buildJsonObject { put("text", "hi") }),
                ),
            ),
            LlmResponse(
                content = "结束",
                toolCalls = listOf(ToolCall("c2", "finish", buildJsonObject { })),
            ),
        )

        val result = AgentLoop(MockLlmClient(script), registry, maxSteps = 10).run("测试目标")

        assertTrue(result.success, "应成功结束")
        assertEquals(1, echo.called, "echo 应被调用一次")
        assertEquals(2, result.steps, "应在第二步 finish")
    }

    @Test
    fun `unknown tool returns error but loop continues to finish`() {
        val registry = ToolRegistry().register(FinishTool())
        val script = listOf(
            LlmResponse(
                toolCalls = listOf(ToolCall("c1", "nope", buildJsonObject { })),
            ),
            LlmResponse(
                toolCalls = listOf(ToolCall("c2", "finish", buildJsonObject { })),
            ),
        )
        val result = AgentLoop(MockLlmClient(script), registry, maxSteps = 5).run("x")
        assertTrue(result.success)
    }
}
