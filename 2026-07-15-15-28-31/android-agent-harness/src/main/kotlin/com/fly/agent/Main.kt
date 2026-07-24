package com.fly.agent

import com.fly.agent.adb.AdbClient
import com.fly.agent.agent.AgentLoop
import com.fly.agent.device.Device
import com.fly.agent.llm.LlmClient
import com.fly.agent.llm.MockLlmClient
import com.fly.agent.llm.OpenAiLlmClient
import com.fly.agent.tools.DeviceTools
import com.fly.agent.tools.ToolRegistry
import com.fly.agent.tools.UiContext
import com.fly.agent.util.Log
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * 命令行入口。
 *
 * 示例:
 *   # 用 Mock 跑通回环(需连一台设备做 get_ui):
 *   ./gradlew run --args="--goal '打开设置并进入关于手机' --mock"
 *
 *   # 接本地 llama.cpp(OpenAI 兼容):
 *   ./gradlew run --args="--goal '打开设置' --base-url http://127.0.0.1:8081/v1 --model qwen"
 *
 * 参数:
 *   --goal <text>        任务目标(必填)
 *   --mock               使用 Mock LLM(默认在未提供 --base-url 时启用)
 *   --adb <path>         adb 路径(默认取环境变量 ADB_PATH 或 "adb")
 *   --serial <serial>    指定设备序列号
 *   --base-url <url>     OpenAI 兼容端点,如 http://127.0.0.1:8081/v1
 *   --api-key <key>      Bearer key(本地服务可省)
 *   --model <name>       模型名(默认 "local-model")
 *   --max-steps <n>      最大步数(默认 25)
 *   --out <dir>          截图/输出目录(默认 ./agent-out)
 */
fun main(argv: Array<String>) {
    val args = parseArgs(argv)

    val goal = args["goal"]
    if (goal.isNullOrBlank()) {
        System.err.println("缺少 --goal。示例: --goal \"打开设置\" --mock")
        exitProcess(2)
    }

    val adbPath = args["adb"] ?: System.getenv("ADB_PATH") ?: "adb"
    val serial = args["serial"]
    val outDir = Paths.get(args["out"] ?: "agent-out").toAbsolutePath()
    val maxSteps = args["max-steps"]?.toIntOrNull() ?: 25

    // 组装设备与工具
    val adb = AdbClient(adbPath, serial)
    val devices = runCatching { adb.listDevices() }.getOrDefault(emptyList())
    Log.info("adb=$adbPath 在线设备: ${if (devices.isEmpty()) "(无)" else devices.joinToString()}")

    val device = Device(adb, outDir)
    val uiContext = UiContext()
    val registry = ToolRegistry()
    DeviceTools(device, uiContext).all().forEach(registry::register)

    // 选择 LLM
    val useMock = args.containsKey("mock") || args["base-url"] == null
    val llm: LlmClient = if (useMock) {
        Log.info("LLM 模式: Mock(仅验证回环)")
        MockLlmClient()
    } else {
        val baseUrl = args["base-url"]!!
        val apiKey = args["api-key"] ?: System.getenv("OPENAI_API_KEY")
        val model = args["model"] ?: System.getenv("OPENAI_MODEL") ?: "local-model"
        Log.info("LLM 模式: OpenAI 兼容 -> $baseUrl ($model)")
        OpenAiLlmClient(baseUrl, apiKey, model)
    }

    val result = AgentLoop(llm, registry, maxSteps).run(goal)

    Log.info("════════ 运行结束 ════════")
    Log.info("成功=${result.success} 步数=${result.steps}")
    Log.info("总结: ${result.summary}")
    exitProcess(if (result.success) 0 else 1)
}

/** 极简 "--key value" / "--flag" 解析。 */
private fun parseArgs(argv: Array<String>): Map<String, String> {
    val map = HashMap<String, String>()
    var i = 0
    while (i < argv.size) {
        val a = argv[i]
        if (a.startsWith("--")) {
            val key = a.removePrefix("--")
            val next = argv.getOrNull(i + 1)
            if (next != null && !next.startsWith("--")) {
                map[key] = next; i += 2
            } else {
                map[key] = "true"; i += 1
            }
        } else i += 1
    }
    return map
}
