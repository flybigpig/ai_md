package com.fly.agent.tools

import com.fly.agent.AgentAccessibilityService
import com.fly.agent.util.AgentLog
import kotlinx.serialization.json.JsonElement

/**
 * 把“设备能力”封装成 LLM 可调用工具。所有动作最终落到
 * AgentAccessibilityService.instance —— 即设备内的无障碍服务。
 *
 * 注意：这些工具在 AgentLoop 的协程上执行，AccessibilityService 的
 * binder 调用可跨线程，dispatchGesture 的回调走主线程 Handler。
 */
fun buildDeviceTools(visionEnabled: Boolean): List<Tool> = listOf(
    GetUiTool(),
    TapIndexTool(),
    TapXyTool(),
    SwipeTool(),
    InputTextTool(),
    PressKeyTool(),
    OpenAppTool(),
    ScreenshotTool(visionEnabled),
    FinishTool()
)

// ---------- 感知 ----------

class GetUiTool : BaseTool(
    "get_ui",
    "获取当前屏幕的结构化无障碍节点树：列出所有可交互元素及其索引、文本、坐标。每次要操作前应先调用以感知界面。"
) {
    override fun schema() = toolDef(name, description, paramsObject())
    override suspend fun run(args: Map<String, JsonElement>): String {
        val svc = AgentAccessibilityService.instance
            ?: return "错误：无障碍服务未连接。请先在系统「设置→无障碍」中开启本应用。"
        val dump = svc.snapshot()
        AgentLog.i("get_ui → ${dump.nodes.size} 个可交互元素")
        return dump.toText()
    }
}

// ---------- 动作：点击 ----------

class TapIndexTool : BaseTool(
    "tap_index",
    "按 get_ui 给出的索引点击一个可交互元素。这是最常用的点击方式。"
) {
    override fun schema() = toolDef(
        name, description,
        paramsObject("index" to param("integer", "get_ui 输出里的元素索引"))
    )

    override suspend fun run(args: Map<String, JsonElement>): String {
        val svc = AgentAccessibilityService.instance
            ?: return "错误：无障碍服务未连接。"
        val idx = int(args, "index", -1)
        if (idx < 0) return "错误：缺少有效的 index"
        val ok = svc.clickIndex(idx)
        return if (ok) "已点击索引 $idx" else "点击索引 $idx 失败（坐标越界或服务未就绪）"
    }
}

class TapXyTool : BaseTool(
    "tap_xy",
    "在屏幕绝对坐标 (x,y) 处点击。仅当没有合适索引时使用。"
) {
    override fun schema() = toolDef(
        name, description,
        paramsObject(
            "x" to param("integer", "横坐标（像素）"),
            "y" to param("integer", "纵坐标（像素）")
        )
    )

    override suspend fun run(args: Map<String, JsonElement>): String {
        val svc = AgentAccessibilityService.instance
            ?: return "错误：无障碍服务未连接。"
        val ok = svc.tap(int(args, "x", -1), int(args, "y", -1))
        return if (ok) "已点击坐标" else "点击失败"
    }
}

// ---------- 动作：滑动 ----------

class SwipeTool : BaseTool(
    "swipe",
    "从 (x1,y1) 滑动到 (x2,y2)。用于滚动列表或滑动手势。"
) {
    override fun schema() = toolDef(
        name, description,
        paramsObject(
            "x1" to param("integer", "起点横坐标"),
            "y1" to param("integer", "起点纵坐标"),
            "x2" to param("integer", "终点横坐标"),
            "y2" to param("integer", "终点纵坐标"),
            "duration_ms" to param("integer", "滑动时长(毫秒)，默认 220")
        )
    )

    override suspend fun run(args: Map<String, JsonElement>): String {
        val svc = AgentAccessibilityService.instance
            ?: return "错误：无障碍服务未连接。"
        val ok = svc.swipe(
            int(args, "x1", 0), int(args, "y1", 0),
            int(args, "x2", 0), int(args, "y2", 0),
            int(args, "duration_ms", 220).toLong()
        )
        return if (ok) "已滑动" else "滑动失败"
    }
}

// ---------- 动作：输入文字 ----------

class InputTextTool : BaseTool(
    "input_text",
    "向某个可编辑文本框填入文字。index 来自 get_ui，必须是 editable 节点。"
) {
    override fun schema() = toolDef(
        name, description,
        paramsObject(
            "index" to param("integer", "get_ui 里的可编辑元素索引"),
            "text" to param("string", "要填入的文字")
        )
    )

    override suspend fun run(args: Map<String, JsonElement>): String {
        val svc = AgentAccessibilityService.instance
            ?: return "错误：无障碍服务未连接。"
        val idx = int(args, "index", -1)
        val text = str(args, "text") ?: return "错误：缺少 text"
        val ok = svc.setTextIndex(idx, text)
        return if (ok) "已在索引 $idx 输入文字" else "输入失败（索引非可编辑或越界）"
    }
}

// ---------- 动作：系统键 ----------

class PressKeyTool : BaseTool(
    "press_key",
    "执行系统全局动作：返回(BACK)/主页(HOME)/多任务(RECENTS)/通知栏(NOTIFICATIONS)。"
) {
    override fun schema() = toolDef(
        name, description,
        paramsObject(
            "key" to param(
                "string", "系统键",
                enumValues = listOf("BACK", "HOME", "RECENTS", "NOTIFICATIONS")
            )
        )
    )

    override suspend fun run(args: Map<String, JsonElement>): String {
        val svc = AgentAccessibilityService.instance
            ?: return "错误：无障碍服务未连接。"
        val key = (str(args, "key") ?: "BACK").uppercase()
        val action = when (key) {
            "BACK" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "HOME" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "RECENTS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return "错误：未知按键 $key"
        }
        val ok = svc.pressGlobal(action)
        return if (ok) "已执行 $key" else "执行 $key 失败"
    }
}

// ---------- 动作：启动 App ----------

class OpenAppTool : BaseTool(
    "open_app",
    "按包名或应用名启动一个 App。例如 spec=\"设置\" 或 spec=\"com.android.settings\"。"
) {
    override fun schema() = toolDef(
        name, description,
        paramsObject("spec" to param("string", "包名或应用显示名"))
    )

    override suspend fun run(args: Map<String, JsonElement>): String {
        val svc = AgentAccessibilityService.instance
            ?: return "错误：无障碍服务未连接。"
        val spec = str(args, "spec") ?: return "错误：缺少 spec"
        val ok = svc.openApp(spec)
        return if (ok) "已尝试启动 $spec" else "启动 $spec 失败"
    }
}

// ---------- 感知：截图（可选 grounding） ----------

class ScreenshotTool(private val visionEnabled: Boolean) : BaseTool(
    "screenshot",
    "截取当前屏幕为图片（仅当开启视觉 grounding 时可用）。返回图片尺寸；把图片回灌视觉模型为多模态下一步。"
) {
    override fun schema() = toolDef(name, description, paramsObject())

    override suspend fun run(args: Map<String, JsonElement>): String {
        val svc = AgentAccessibilityService.instance
            ?: return "错误：无障碍服务未连接。"
        if (!visionEnabled) return "截图未启用：本次会话未开启视觉 grounding。"
        if (!svc.hasProjection()) return "截图不可用：未授权 MediaProjection（请在 App 中点击「截图授权」）。"
        val bytes = svc.captureScreenshot()
        return if (bytes != null) {
            // 多模态回灌（把 PNG 喂给视觉模型）为下一步扩展点，见 README。
            "已截图：${bytes.size} 字节"
        } else "截图失败"
    }
}

// ---------- 结束 ----------

class FinishTool : BaseTool(
    "finish",
    "任务已完成或无法继续时调用。summary 简述结果。"
) {
    override val isFinish: Boolean get() = true
    override fun schema() = toolDef(
        name, description,
        paramsObject("summary" to param("string", "任务结果简述"))
    )

    override suspend fun run(args: Map<String, JsonElement>): String {
        val summary = str(args, "summary") ?: "（无摘要）"
        AgentLog.i("finish: $summary")
        return "DONE: $summary"
    }
}
