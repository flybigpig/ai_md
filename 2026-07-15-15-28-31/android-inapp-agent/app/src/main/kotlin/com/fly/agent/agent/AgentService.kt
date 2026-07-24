package com.fly.agent.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.fly.agent.AgentAccessibilityService
import com.fly.agent.llm.MockLlmClient
import com.fly.agent.llm.OpenAiLlmClient
import com.fly.agent.tools.ToolRegistry
import com.fly.agent.tools.buildDeviceTools
import com.fly.agent.ui.MainActivity
import com.fly.agent.util.AgentLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务：在设备上持续运行 agent 回环。
 * 通过 AgentState 读取 MainActivity 写入的目标 / LLM 配置。
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (AgentState.isRunning.value) {
            AgentLog.w("回环已在运行，忽略重复启动")
            return START_NOT_STICKY
        }
        startForegroundWithType()
        AgentState.isRunning.value = true

        val llm = if (AgentState.useMock) {
            MockLlmClient(AgentState.goal)
        } else {
            OpenAiLlmClient(AgentState.baseUrl, AgentState.model)
        }
        val tools = ToolRegistry(buildDeviceTools(AgentState.useVision))

        if (AgentState.useVision) {
            AgentState.projectionData?.let {
                AgentAccessibilityService.instance?.prepareProjection(it)
            } ?: AgentLog.w("开启视觉但未获得 MediaProjection 授权，截图将不可用")
        }

        AgentLog.i("启动回环 | LLM=${llm.id} | goal=${AgentState.goal} | vision=${AgentState.useVision}")

        job = scope.launch {
            try {
                AgentLoop(llm, tools, AgentState.goal, AgentState.maxSteps).run()
            } catch (t: Throwable) {
                AgentLog.e("回环异常终止", t)
            } finally {
                AgentState.isRunning.value = false
                if (AgentState.useVision) AgentAccessibilityService.instance?.teardownProjection()
                AgentLog.i("回环结束，停止服务")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        AgentState.isRunning.value = false
        super.onDestroy()
    }

    // ---------- 通知 / 前台化 ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Agent 回环", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("设备内 Agent 运行中")
            .setContentText("目标：${AgentState.goal}")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundWithType() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val CHANNEL_ID = "agent_loop"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AgentService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentService::class.java))
        }
    }
}
