package com.fly.agent.util

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 极简彩色日志,避免引入 slf4j 等重依赖。 */
object Log {
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private const val GRAY = "\u001B[90m"
    private const val CYAN = "\u001B[36m"
    private const val YELLOW = "\u001B[33m"
    private const val RED = "\u001B[31m"
    private const val GREEN = "\u001B[32m"
    private const val RESET = "\u001B[0m"

    private fun ts() = LocalTime.now().format(fmt)

    fun debug(msg: String) = println("$GRAY${ts()} DEBUG $msg$RESET")
    fun info(msg: String) = println("$CYAN${ts()} INFO $RESET $msg")
    fun step(msg: String) = println("$GREEN${ts()} STEP $RESET $msg")
    fun warn(msg: String) = println("$YELLOW${ts()} WARN $RESET $msg")
    fun error(msg: String) = println("$RED${ts()} ERROR$RESET $msg")
}
