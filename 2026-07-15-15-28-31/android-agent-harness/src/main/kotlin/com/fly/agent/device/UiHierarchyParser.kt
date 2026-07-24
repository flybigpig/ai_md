package com.fly.agent.device

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 解析 `uiautomator dump` 产生的 XML 层级,转成 [UiNode] 树。
 * 遍历顺序采用先序(DFS),为每个节点分配自增 index。
 */
object UiHierarchyParser {

    private val factory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        // 关闭 DTD/外部实体,防御 XXE
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isNamespaceAware = false
    }

    fun parse(xml: String): UiNode {
        val doc = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        doc.documentElement.normalize()

        val counter = intArrayOf(0)
        // 根 <hierarchy> 下通常只有一个根 node;这里包一个虚拟根统一处理。
        val root = UiNode(index = -1, className = "root")
        val hierarchy = doc.documentElement // <hierarchy>
        forEachChildElement(hierarchy) { child ->
            root.children.add(convert(child, counter))
        }
        return root
    }

    private fun convert(el: Element, counter: IntArray): UiNode {
        val idx = counter[0]++
        val node = UiNode(
            index = idx,
            className = el.getAttribute("class"),
            text = el.getAttribute("text"),
            contentDesc = el.getAttribute("content-desc"),
            resourceId = el.getAttribute("resource-id"),
            packageName = el.getAttribute("package"),
            clickable = el.getAttribute("clickable") == "true",
            focusable = el.getAttribute("focusable") == "true",
            enabled = el.getAttribute("enabled") != "false",
            bounds = Bounds.parse(el.getAttribute("bounds")),
        )
        forEachChildElement(el) { child ->
            node.children.add(convert(child, counter))
        }
        return node
    }

    private inline fun forEachChildElement(parent: Element, action: (Element) -> Unit) {
        val list = parent.childNodes
        for (i in 0 until list.length) {
            val n = list.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) action(n as Element)
        }
    }

    /** 扁平化出所有节点(先序),便于按 index 查找与打印。 */
    fun flatten(root: UiNode): List<UiNode> {
        val out = ArrayList<UiNode>()
        fun dfs(n: UiNode) {
            if (n.index >= 0) out.add(n)
            n.children.forEach(::dfs)
        }
        dfs(root)
        return out
    }
}
