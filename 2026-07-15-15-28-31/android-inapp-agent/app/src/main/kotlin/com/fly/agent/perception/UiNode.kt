package com.fly.agent.perception

/**
 * 一个可被 LLM 引用的界面节点（结构体，便于序列化/打印）。
 *
 * 注意：这里只保存“快照”信息（坐标、文本、标志位），不持有
 * AccessibilityNodeInfo 引用——后者在回环跨步时可能被系统回收。
 * 需要操作节点时（点击/填字）由 Perception 按 index 重新遍历解析。
 */
data class UiNode(
    val index: Int,
    val text: String?,
    val desc: String?,
    val cls: String?,
    val pkg: String?,
    val resId: String?,
    /** [left, top, right, bottom] 屏幕绝对坐标 */
    val bounds: IntArray,
    /** [centerX, centerY] */
    val center: IntArray,
    val flags: List<String>
) {
    fun boundsStr(): String = "(${bounds[0]},${bounds[1]},${bounds[2]},${bounds[3]})"
    fun centerX(): Int = center[0]
    fun centerY(): Int = center[1]

    /** 给 LLM 看的紧凑一行描述 */
    fun toLine(): String {
        val label = (text?.takeIf { it.isNotBlank() }
            ?: desc?.takeIf { it.isNotBlank() }
            ?: resId?.substringAfter('/')
            ?: cls) ?: ""
        val clsShort = cls?.substringAfterLast('.') ?: "?"
        val flagStr = if (flags.isEmpty()) "" else " " + flags.joinToString(",")
        return "[$index] <$clsShort> \"$label\" ${boundsStr()}$flagStr"
    }
}
