package com.fly.agent.device

import kotlinx.serialization.Serializable

/** 屏幕上的一个矩形区域(像素)。 */
@Serializable
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    companion object {
        // 形如 "[0,84][1080,210]"
        private val RE = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
        fun parse(s: String): Bounds? {
            val m = RE.find(s) ?: return null
            val (l, t, r, b) = m.destructured
            return Bounds(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        }
    }
}

/**
 * UI 层级树节点,是从 uiautomator dump 的 XML 转换来的简化模型。
 * `index` 是遍历时分配的稳定序号,供 LLM 以 "点击 index=N" 的方式引用元素。
 */
@Serializable
data class UiNode(
    val index: Int,
    val className: String = "",
    val text: String = "",
    val contentDesc: String = "",
    val resourceId: String = "",
    val packageName: String = "",
    val clickable: Boolean = false,
    val focusable: Boolean = false,
    val enabled: Boolean = true,
    val bounds: Bounds? = null,
    val children: MutableList<UiNode> = mutableListOf(),
) {
    /** 是否是「对 LLM 有意义」的可交互/带文字元素,用于精简 observation。 */
    fun isInteresting(): Boolean =
        clickable || text.isNotBlank() || contentDesc.isNotBlank() ||
            resourceId.isNotBlank()

    /** 单行摘要,拼给 LLM 看。 */
    fun toLine(): String {
        val sb = StringBuilder("[$index] ")
        sb.append(className.substringAfterLast('.'))
        if (text.isNotBlank()) sb.append(" text=\"${text.take(60)}\"")
        if (contentDesc.isNotBlank()) sb.append(" desc=\"${contentDesc.take(40)}\"")
        if (resourceId.isNotBlank()) sb.append(" id=${resourceId.substringAfterLast('/')}")
        if (clickable) sb.append(" [clickable]")
        bounds?.let { sb.append(" @(${it.centerX},${it.centerY})") }
        return sb.toString()
    }
}
