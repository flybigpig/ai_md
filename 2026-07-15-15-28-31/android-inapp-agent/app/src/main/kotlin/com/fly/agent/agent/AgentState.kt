package com.fly.agent.agent

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 跨组件共享的运行态：MainActivity 写配置，AgentService 读配置跑回环。
 */
object AgentState {
    val isRunning = MutableStateFlow(false)

    var goal: String = "打开设置并进入关于手机"
    var baseUrl: String = "http://127.0.0.1:8081/v1"
    var model: String = "local-model"
    var useMock: Boolean = false
    var useVision: Boolean = false

    /** MainActivity 拿到 MediaProjection 授权后写入，供截图使用 */
    var projectionData: Intent? = null

    var maxSteps: Int = 30
}
