package com.fly.agent.tools

import com.fly.agent.device.Device
import com.fly.agent.device.UiHierarchyParser
import com.fly.agent.device.UiNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

// ---------- 参数读取辅助 ----------
private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

/**
 * 把 [Device] 的能力封装成一组 [Tool]。
 * 所有工具共享同一个 [UiContext],使 get_ui 与 tap_index 之间能对齐元素编号。
 */
class DeviceTools(
    private val device: Device,
    private val uiContext: UiContext,
) {
    fun all(): List<Tool> = listOf(
        GetUi(), Screenshot(), TapIndex(), TapXy(), Swipe(),
        InputText(), PressKey(), OpenApp(), Finish(),
    )

    /** 读取当前界面 UI 树摘要(只保留有意义的元素),并刷新 index 映射。 */
    inner class GetUi : Tool {
        override val name = "get_ui"
        override val description =
            "获取当前屏幕的可交互元素列表(带 index、文本、坐标)。决定点击前应先调用它。"
        override val parametersSchema = emptySchema()
        override fun execute(args: JsonObject): ToolResult {
            val root = device.uiHierarchy()
            val nodes = UiHierarchyParser.flatten(root)
            uiContext.update(nodes)
            val interesting = nodes.filter(UiNode::isInteresting)
            val activity = device.currentActivity()
            val body = interesting.joinToString("\n") { it.toLine() }
            return ToolResult("当前界面: $activity\n共 ${interesting.size} 个可交互元素:\n$body")
        }
    }

    /** 截屏保存,供人工/多模态观察。 */
    inner class Screenshot : Tool {
        override val name = "screenshot"
        override val description = "对当前屏幕截图并保存为 PNG 文件。"
        override val parametersSchema = schemaOf(
            Prop("name", "string", "文件名(不含扩展名)", required = false),
        )
        override fun execute(args: JsonObject): ToolResult {
            val name = args.str("name") ?: "shot_${System.currentTimeMillis()}"
            val f = device.screenshot(name)
            return ToolResult("已截图: ${f.absolutePath}")
        }
    }

    /** 按 get_ui 给出的 index 点击元素中心。 */
    inner class TapIndex : Tool {
        override val name = "tap_index"
        override val description = "点击 get_ui 返回列表中某个元素(通过其 index)。"
        override val parametersSchema = schemaOf(
            Prop("index", "integer", "get_ui 列表里的元素编号"),
        )
        override fun execute(args: JsonObject): ToolResult {
            val idx = args.int("index")
                ?: return ToolResult("缺少参数 index")
            val node = uiContext.node(idx)
                ?: return ToolResult("找不到 index=$idx 的元素,请先调用 get_ui 刷新。")
            val b = node.bounds
                ?: return ToolResult("index=$idx 的元素没有坐标信息,无法点击。")
            device.tap(b.centerX, b.centerY)
            return ToolResult("已点击 [$idx] ${node.className.substringAfterLast('.')} @(${b.centerX},${b.centerY})")
        }
    }

    /** 直接按坐标点击。 */
    inner class TapXy : Tool {
        override val name = "tap_xy"
        override val description = "按屏幕绝对坐标点击。"
        override val parametersSchema = schemaOf(
            Prop("x", "integer", "横坐标像素"),
            Prop("y", "integer", "纵坐标像素"),
        )
        override fun execute(args: JsonObject): ToolResult {
            val x = args.int("x") ?: return ToolResult("缺少参数 x")
            val y = args.int("y") ?: return ToolResult("缺少参数 y")
            device.tap(x, y)
            return ToolResult("已点击 ($x,$y)")
        }
    }

    /** 滑动(支持方向语义或显式坐标)。 */
    inner class Swipe : Tool {
        override val name = "swipe"
        override val description = "滑动屏幕。可给方向(up/down/left/right)做半屏滚动,或给显式坐标。"
        override val parametersSchema = schemaOf(
            Prop("direction", "string", "方向", required = false,
                enumValues = listOf("up", "down", "left", "right")),
            Prop("x1", "integer", "起点 x(显式坐标时)", required = false),
            Prop("y1", "integer", "起点 y", required = false),
            Prop("x2", "integer", "终点 x", required = false),
            Prop("y2", "integer", "终点 y", required = false),
        )
        override fun execute(args: JsonObject): ToolResult {
            val dir = args.str("direction")
            if (dir != null) {
                val (w, h) = device.screenSize()
                val cx = w / 2; val cy = h / 2
                val dx = w / 4; val dy = h / 4
                when (dir) {
                    "up" -> device.swipe(cx, cy + dy, cx, cy - dy)
                    "down" -> device.swipe(cx, cy - dy, cx, cy + dy)
                    "left" -> device.swipe(cx + dx, cy, cx - dx, cy)
                    "right" -> device.swipe(cx - dx, cy, cx + dx, cy)
                    else -> return ToolResult("未知方向: $dir")
                }
                return ToolResult("已向 $dir 滑动")
            }
            val x1 = args.int("x1"); val y1 = args.int("y1")
            val x2 = args.int("x2"); val y2 = args.int("y2")
            if (x1 == null || y1 == null || x2 == null || y2 == null) {
                return ToolResult("需要 direction 或完整的 x1,y1,x2,y2")
            }
            device.swipe(x1, y1, x2, y2)
            return ToolResult("已滑动 ($x1,$y1)->($x2,$y2)")
        }
    }

    /** 向当前焦点输入文本。 */
    inner class InputText : Tool {
        override val name = "input_text"
        override val description = "向当前获得焦点的输入框键入文本(需先点击输入框)。"
        override val parametersSchema = schemaOf(
            Prop("text", "string", "要输入的文本"),
        )
        override fun execute(args: JsonObject): ToolResult {
            val text = args.str("text") ?: return ToolResult("缺少参数 text")
            device.inputText(text)
            return ToolResult("已输入: \"$text\"")
        }
    }

    /** 系统按键。 */
    inner class PressKey : Tool {
        override val name = "press_key"
        override val description = "发送系统按键,如 back/home/enter。"
        override val parametersSchema = schemaOf(
            Prop("key", "string", "按键", enumValues = listOf("back", "home", "enter", "delete")),
        )
        override fun execute(args: JsonObject): ToolResult {
            val key = args.str("key") ?: return ToolResult("缺少参数 key")
            val code = when (key) {
                "back" -> "KEYCODE_BACK"
                "home" -> "KEYCODE_HOME"
                "enter" -> "KEYCODE_ENTER"
                "delete" -> "KEYCODE_DEL"
                else -> return ToolResult("未知按键: $key")
            }
            device.pressKey(code)
            return ToolResult("已按 $key")
        }
    }

    /** 按包名启动应用。 */
    inner class OpenApp : Tool {
        override val name = "open_app"
        override val description = "通过包名启动一个应用,例如 com.android.settings。"
        override val parametersSchema = schemaOf(
            Prop("package", "string", "应用包名"),
        )
        override fun execute(args: JsonObject): ToolResult {
            val pkg = args.str("package") ?: return ToolResult("缺少参数 package")
            device.openApp(pkg)
            return ToolResult("已启动 $pkg")
        }
    }

    /** 终结任务。 */
    inner class Finish : Tool {
        override val name = "finish"
        override val description = "当任务完成或确定无法完成时调用,结束整个回环。"
        override val parametersSchema = schemaOf(
            Prop("success", "boolean", "任务是否成功"),
            Prop("summary", "string", "结果说明"),
        )
        override fun execute(args: JsonObject): ToolResult {
            val success = (args["success"] as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: false
            val summary = args.str("summary") ?: ""
            return ToolResult(output = summary, finished = true, success = success)
        }
    }
}
