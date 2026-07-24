package com.fly.agent.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fly.agent.AgentAccessibilityService
import com.fly.agent.agent.AgentService
import com.fly.agent.agent.AgentState
import com.fly.agent.databinding.ActivityMainBinding
import com.fly.agent.util.AgentLog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logBuf = StringBuilder()

    /** 截屏授权结果：拿到后写入 AgentState 并启动回环 */
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            AgentState.projectionData = result.data
            AgentLog.i("已获得截屏授权")
        } else {
            AgentLog.w("未授予截屏权限，将以无截图模式运行")
        }
        AgentService.start(this)
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 授权与否都不影响回环，仅影响通知展示 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 回填默认值
        binding.editGoal.setText(AgentState.goal)
        binding.editBaseUrl.setText(AgentState.baseUrl)
        binding.editModel.setText(AgentState.model)

        binding.btnEnableAcc.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnStop.setOnClickListener {
            AgentService.stop(this)
        }

        // 运行状态
        lifecycleScope.launch {
            AgentState.isRunning.collectLatest { running ->
                binding.txtStatus.text = if (running) "运行中…" else "空闲"
                binding.btnStart.isEnabled = !running
            }
        }

        // 日志流
        lifecycleScope.launch {
            AgentLog.events.collect { line ->
                logBuf.appendLine(line)
                if (logBuf.length > 6000) logBuf.delete(0, logBuf.length - 4000)
                binding.txtLog.text = logBuf.toString()
            }
        }

        // Android 13+ 申请通知权限（可选）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        refreshAccHint()
    }

    private fun onStartClicked() {
        AgentState.goal = binding.editGoal.text.toString().ifBlank { "打开设置" }
        AgentState.baseUrl = binding.editBaseUrl.text.toString().ifBlank { "http://127.0.0.1:8081/v1" }
        AgentState.model = binding.editModel.text.toString().ifBlank { "local-model" }
        AgentState.useMock = binding.chkMock.isChecked
        AgentState.useVision = binding.chkVision.isChecked

        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先开启「设备内 Agent」无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        if (AgentState.useVision && AgentState.projectionData == null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            captureLauncher.launch(mpm.createScreenCaptureIntent())
            return
        }
        AgentService.start(this)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val cmp = ComponentName(this, AgentAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(cmp, ignoreCase = true) }
    }

    private fun refreshAccHint() {
        if (!isAccessibilityEnabled()) {
            AgentLog.w("无障碍服务未开启：请点击「开启无障碍服务」并在设置中启用本应用")
        } else {
            AgentLog.i("无障碍服务已开启")
        }
    }
}
