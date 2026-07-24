package com.fly.agent.device

import com.fly.agent.adb.AdbClient
import com.fly.agent.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * 设备抽象:把「感知」(截图 / UI 树 / 屏幕信息)与「动作」(点击 / 滑动 / 输入 / 按键 / 启动应用)
 * 统一收敛到这里。上层 Tool 只依赖本类,不直接碰 adb。
 */
class Device(
    private val adb: AdbClient,
    private val outputDir: Path,
) {
    init {
        Files.createDirectories(outputDir)
    }

    /** 屏幕尺寸(宽,高),像素。 */
    fun screenSize(): Pair<Int, Int> {
        // 形如 "Physical size: 1080x2340"
        val out = adb.shell("wm", "size")
        val m = Regex("""(\d+)x(\d+)""").find(out)
            ?: return 1080 to 1920
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /** 当前前台包名/Activity(用于让 agent 知道自己在哪)。 */
    fun currentActivity(): String {
        val out = runCatching {
            adb.shell("dumpsys", "activity", "activities")
        }.getOrDefault("")
        val m = Regex("""mResumedActivity.*?\{[^ ]* ([^ ]+/[^ }]+)""").find(out)
        return m?.groupValues?.get(1) ?: "unknown"
    }

    // ---------------- 感知 ----------------

    /** 截图并保存为 PNG,返回本地文件路径。 */
    fun screenshot(name: String): File {
        val r = adb.exec("exec-out", "screencap", "-p", timeoutSec = 30)
        if (!r.ok || r.stdout.isEmpty()) {
            throw RuntimeException("截图失败: ${r.stderr}")
        }
        val file = outputDir.resolve("$name.png").toFile()
        file.writeBytes(r.stdout)
        Log.debug("截图已保存: ${file.absolutePath} (${r.stdout.size} bytes)")
        return file
    }

    /** dump 当前界面的 UI 层级树。 */
    fun uiHierarchy(): UiNode {
        // 优先用 exec-out 直接把 XML 输出到 stdout,避免落盘再 pull。
        val xml = runCatching {
            adb.shell("uiautomator", "dump", "/dev/tty", timeoutSec = 20)
        }.getOrNull()

        val effective = if (xml != null && xml.contains("<hierarchy")) {
            xml.substring(xml.indexOf("<?xml").let { if (it >= 0) it else xml.indexOf("<hierarchy") })
        } else {
            // 回退:dump 到文件再 cat
            adb.shell("uiautomator", "dump", "/sdcard/agent_ui.xml", timeoutSec = 20)
            adb.shell("cat", "/sdcard/agent_ui.xml", timeoutSec = 20)
        }
        return UiHierarchyParser.parse(effective)
    }

    // ---------------- 动作 ----------------

    fun tap(x: Int, y: Int) {
        adb.shell("input", "tap", x.toString(), y.toString())
        Log.step("tap ($x, $y)")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300) {
        adb.shell("input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString())
        Log.step("swipe ($x1,$y1)->($x2,$y2) ${durationMs}ms")
    }

    /** 输入文本。空格转义为 %s,避免 shell 拆词。 */
    fun inputText(text: String) {
        val escaped = text.replace(" ", "%s")
        adb.shell("input", "text", escaped)
        Log.step("input text \"$text\"")
    }

    /** 按下 keyevent,如 KEYCODE_BACK / KEYCODE_HOME / KEYCODE_ENTER。 */
    fun pressKey(keycode: String) {
        adb.shell("input", "keyevent", keycode)
        Log.step("keyevent $keycode")
    }

    fun back() = pressKey("KEYCODE_BACK")
    fun home() = pressKey("KEYCODE_HOME")

    /** 通过 monkey 以包名启动应用(无需知道 Activity)。 */
    fun openApp(packageName: String) {
        adb.shell(
            "monkey", "-p", packageName,
            "-c", "android.intent.category.LAUNCHER", "1",
            timeoutSec = 20,
        )
        Log.step("open app $packageName")
    }
}
