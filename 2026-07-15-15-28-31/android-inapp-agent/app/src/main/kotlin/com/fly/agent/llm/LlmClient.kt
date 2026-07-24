package com.fly.agent.llm

/**
 * LLM 抽象。agent 回环只依赖此接口：
 * 传入对话历史 + 工具定义，返回一条 assistant 消息
 *（可能带 content，也可能带 tool_calls）。
 */
interface LlmClient {
    val id: String

    /**
     * @param messages 完整对话历史（含 system/user/tool 角色）
     * @param tools    可用工具定义（模型不支持时忽略即可）
     * @return assistant 消息（content 或 tool_calls 至少其一）
     */
    suspend fun chat(messages: List<ChatMessage>, tools: List<ToolDef>): ChatMessage
}
