package com.fly.agent.tools

import com.fly.agent.device.UiNode

/**
 * 保存「最近一次 UI dump」的扁平节点表,供按 index 引用元素的工具使用。
 * get_ui 每次刷新它,tap_index 从中查坐标。
 */
class UiContext {
    private var byIndex: Map<Int, UiNode> = emptyMap()

    fun update(nodes: List<UiNode>) {
        byIndex = nodes.associateBy { it.index }
    }

    fun node(index: Int): UiNode? = byIndex[index]
}
