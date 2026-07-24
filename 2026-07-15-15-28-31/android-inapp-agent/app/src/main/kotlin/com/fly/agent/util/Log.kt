package com.fly.agent.util

import android.util.Log as ALog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App 内日志总线：同时打 Logcat 并通过 SharedFlow 推给 MainActivity 显示。
 * 用 tryEmit 避免在非协程/后台线程上挂起。
 */
object AgentLog {
    private const val TAG = "InAppAgent"

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1024)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun i(msg: String) {
        ALog.i(TAG, msg)
        _events.tryEmit(msg)
    }

    fun w(msg: String) {
        ALog.w(TAG, msg)
        _events.tryEmit("⚠ $msg")
    }

    fun e(msg: String, t: Throwable? = null) {
        ALog.e(TAG, msg, t)
        _events.tryEmit("✗ $msg${t?.let { " -> ${it.message}" } ?: ""}")
    }

    fun d(msg: String) {
        ALog.d(TAG, msg)
        _events.tryEmit("· $msg")
    }
}
