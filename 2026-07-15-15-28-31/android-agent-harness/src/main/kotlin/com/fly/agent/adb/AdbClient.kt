package com.fly.agent.adb

import com.fly.agent.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 极简 ADB 封装。所有设备能力最终都落到 `adb [-s serial] <args...>`。
 *
 * @param adbPath   adb 可执行文件路径(Windows 下形如 C:\D\SDK\platform-tools\adb.exe)
 * @param serial    目标设备序列号;为 null 时由 adb 自行选择(仅一台设备时可用)
 */
class AdbClient(
    private val adbPath: String,
    private val serial: String? = null,
) {

    data class ExecResult(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: String,
    ) {
        val stdoutText: String get() = stdout.toString(Charsets.UTF_8)
        val ok: Boolean get() = exitCode == 0
    }

    private fun baseArgs(): List<String> =
        if (serial != null) listOf(adbPath, "-s", serial) else listOf(adbPath)

    /** 执行任意 adb 子命令,返回原始结果(stdout 以字节保存,兼容截图等二进制)。 */
    fun exec(vararg args: String, timeoutSec: Long = 30): ExecResult {
        val cmd = baseArgs() + args.toList()
        Log.debug("adb ${args.joinToString(" ")}")
        val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()

        val out = ByteArrayOutputStream()
        val outThread = Thread { proc.inputStream.copyTo(out) }.apply { start() }
        val err = ByteArrayOutputStream()
        val errThread = Thread { proc.errorStream.copyTo(err) }.apply { start() }

        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw RuntimeException("adb 命令超时(${timeoutSec}s): ${args.joinToString(" ")}")
        }
        outThread.join()
        errThread.join()
        return ExecResult(proc.exitValue(), out.toByteArray(), err.toString(Charsets.UTF_8))
    }

    /** 执行 `adb shell ...`,返回 stdout 文本(去除尾部换行)。 */
    fun shell(vararg args: String, timeoutSec: Long = 30): String {
        val r = exec(*(arrayOf("shell") + args), timeoutSec = timeoutSec)
        if (!r.ok) throw RuntimeException("adb shell 失败: ${args.joinToString(" ")}\n${r.stderr}")
        return r.stdoutText.trimEnd('\n', '\r')
    }

    /** 列出在线设备序列号。 */
    fun listDevices(): List<String> {
        val r = exec("devices")
        return r.stdoutText.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1] == "device") parts[0] else null
            }
            .toList()
    }
}
