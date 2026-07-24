package com.fly.agent.perception

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 把 AccessibilityNodeInfo 树转成 LLM 友好的“扁平可交互节点列表”。
 *
 * 关键设计：
 * 1. 索引 index 按**先序遍历中“可提及节点”的出现顺序**确定性分配，
 *    因此感知(snapshot)与解析(resolve)用同一套顺序，跨步可对齐。
 * 2. snapshot 只拷贝节点快照，立即 recycle 原始 node，避免泄漏。
 * 3. resolve 重新遍历当前树取第 index 个“可提及节点”返回**活引用**，
 *    调用方负责 recycle。
 */
object Perception {

    /** 一次 UI 快照 */
    data class UiDump(
        val nodes: List<UiNode>,
        val screenW: Int,
        val screenH: Int,
        val foregroundPackage: String?
    ) {
        /** 喂给 LLM / 作为 get_ui 工具返回值的文本 */
        fun toText(): String {
            val sb = StringBuilder()
            sb.appendLine("屏幕: ${screenW}x$screenH  前台包: ${foregroundPackage ?: "?"}")
            sb.appendLine("可交互元素(${nodes.size}):")
            if (nodes.isEmpty()) {
                sb.appendLine("  (无可见可交互元素)")
            } else {
                for (n in nodes) sb.appendLine("  ${n.toLine()}")
            }
            sb.appendLine("提示: 用 tap_index(<索引>) 点击; 文本框用 input_text(<索引>, \"内容\")。")
            return sb.toString()
        }
    }

    /** 判定一个节点是否值得让 LLM 引用 */
    private fun mentionable(n: AccessibilityNodeInfo): Boolean {
        val hasText = (!n.text?.toString().isNullOrBlank()) ||
                (!n.contentDescription?.toString().isNullOrBlank())
        val interactive = n.isClickable || n.isEditable || n.isScrollable ||
                n.isCheckable || n.isLongClickable
        return interactive || (n.childCount == 0 && hasText)
    }

    fun snapshot(
        service: AccessibilityService,
        root: AccessibilityNodeInfo?
    ): UiDump {
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val nodes = mutableListOf<UiNode>()
        val idx = intArrayOf(0)
        traverseSnapshot(root, nodes, idx)
        val pkg = root?.packageName?.toString()
        return UiDump(nodes, w, h, pkg)
    }

    private fun traverseSnapshot(
        node: AccessibilityNodeInfo?,
        out: MutableList<UiNode>,
        idx: IntArray
    ) {
        if (node == null) return
        if (mentionable(node)) {
            out.add(buildUiNode(node, idx[0]))
            idx[0]++
        }
        for (i in 0 until node.childCount) {
            traverseSnapshot(node.getChild(i), out, idx)
        }
        node.recycle()
    }

    private fun buildUiNode(n: AccessibilityNodeInfo, index: Int): UiNode {
        val r = Rect()
        n.getBoundsInScreen(r)
        val cx = (r.left + r.right) / 2
        val cy = (r.top + r.bottom) / 2
        val flags = mutableListOf<String>()
        if (n.isClickable) flags.add("clickable")
        if (n.isEditable) flags.add("editable")
        if (n.isScrollable) flags.add("scrollable")
        if (n.isCheckable) flags.add("checkable")
        if (n.isChecked) flags.add("checked")
        if (n.isFocused) flags.add("focused")
        if (n.isSelected) flags.add("selected")
        if (n.isLongClickable) flags.add("longclickable")
        return UiNode(
            index = index,
            text = n.text?.toString(),
            desc = n.contentDescription?.toString(),
            cls = n.className?.toString(),
            pkg = n.packageName?.toString(),
            resId = n.viewIdResourceName,
            bounds = intArrayOf(r.left, r.top, r.right, r.bottom),
            center = intArrayOf(cx, cy),
            flags = flags
        )
    }

    /**
     * 重新遍历当前树，返回第 [index] 个“可提及节点”的**活引用**。
     * 调用方用完后必须 recycle。其余被访问到的节点在此函数内回收。
     */
    fun resolve(root: AccessibilityNodeInfo?, index: Int): AccessibilityNodeInfo? {
        val collected = mutableListOf<AccessibilityNodeInfo>()
        val idx = intArrayOf(0)
        collectMentionable(root, collected, idx)
        if (index < 0 || index >= collected.size) {
            // 回收全部，返回 null
            collected.forEach { it.recycle() }
            return null
        }
        val chosen = collected[index]
        collected.forEachIndexed { i, node -> if (i != index) node.recycle() }
        return chosen
    }

    private fun collectMentionable(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        idx: IntArray
    ) {
        if (node == null) return
        if (mentionable(node)) {
            out.add(node)
            idx[0]++
        } else {
            node.recycle()
        }
        for (i in 0 until node.childCount) {
            collectMentionable(node.getChild(i), out, idx)
        }
    }
}
