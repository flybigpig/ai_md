package com.fly.agent.agent

import com.fly.agent.llm.LlmClient
import com.fly.agent.llm.Message
import com.fly.agent.tools.ToolRegistry
import com.fly.agent.util.Log

/** 单次运行的结果。 */
data class RunResult(
    val success: Boolean,
    val steps: Int,
    val summary: String,
    val transcript: List<Message>,
)

/**
 * 核心「感知-决策-行动」回环。
 *
 * 流程:
 *   system+user(goal) -> LLM -> (可能的 tool_calls) -> 执行工具 -> 结果回喂 -> LLM -> ...
 * 直到 LLM 调用 finish 或达到 maxSteps。
 */
class AgentLoop(
    private val llm: LlmClient,
    private val tools: ToolRegistry,
    private val maxSteps: Int = 25,
) {
    fun run(goal: String): RunResult {
        val messages = mutableListOf(
            Message.system(Prompts.system(goal)),
            Message.user("请开始执行任务。"),
        )
        val specs = tools.toSpecs()
        Log.info("使用 LLM: ${llm.label} | 可用工具: ${specs.joinToString { it.name }}")
        Log.info("目标: $goal")

        var step = 0
        while (step < maxSteps) {
            step++
            Log.info("──────── Step $step ────────")

            val resp = llm.chat(messages, specs)
            if (resp.content.isNotBlank()) Log.info("思考: ${resp.content}")

            // LLM 没有发起工具调用:视为纯文本回复,追加后继续给它一次机会推进。
            if (!resp.hasToolCalls) {
                messages.add(Message.assistant(resp.content))
                messages.add(Message.user("请调用一个工具来推进任务,或调用 finish 结束。"))
                continue
            }

            // 记录 assistant 的工具调用
            messages.add(Message.assistant(resp.content, resp.toolCalls))

            var finished = false
            var finishSuccess = false
            var finishSummary = ""

            for (call in resp.toolCalls) {
                Log.step("→ 工具 ${call.name} 参数=${call.arguments}")
                val result = tools.invoke(call.name, call.arguments)
                Log.debug("← 结果: ${result.output.take(300)}")
                messages.add(Message.tool(call.id, call.name, result.output))

                if (result.finished) {
                    finished = true
                    finishSuccess = result.success
                    finishSummary = result.output
                    break
                }
            }

            if (finished) {
                Log.info(if (finishSuccess) "✅ 任务完成: $finishSummary" else "⛔ 任务结束(未成功): $finishSummary")
                return RunResult(finishSuccess, step, finishSummary, messages)
            }
        }

        Log.warn("达到最大步数 $maxSteps,强制结束。")
        return RunResult(false, step, "达到最大步数未完成", messages)
    }
}
