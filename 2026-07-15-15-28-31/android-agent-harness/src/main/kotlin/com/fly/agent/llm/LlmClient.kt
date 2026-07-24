package com.fly.agent.llm

/** LLM 客户端抽象。上层 AgentLoop 只依赖它,便于在 Mock 与真实模型间切换。 */
interface LlmClient {
    /**
     * 给定完整对话历史与可用工具,返回下一步(文本 + 可选 tool 调用)。
     */
    fun chat(messages: List<Message>, tools: List<ToolSpec>): LlmResponse

    /** 便于日志区分。 */
    val label: String
}
