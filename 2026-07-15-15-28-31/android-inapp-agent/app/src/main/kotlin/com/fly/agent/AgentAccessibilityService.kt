package com.fly.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.fly.agent.perception.Perception
import com.fly.agent.util.AgentLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * 设备内 agent 的“手和眼”：
 * - 眼：getRootInActiveWindow() → Perception 转扁平可交互节点树（结构化感知）
 * - 手：dispatchGesture 做坐标级点击/滑动；节点 ACTION_SET_TEXT 填字；
 *       performGlobalAction 做 BACK/HOME 等系统动作；包名/label 启动 App
 * - 眼(像素)：MediaProjection 截图，供视觉 grounding（可选）
 *
 * 通过 companion.instance 暴露给 AgentService / 工具层调用。
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- 截图相关（MediaProjection）----
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AgentLog.i("AccessibilityService 已连接")
    }

    override fun onDestroy() {
        super.onDestroy()
        teardownProjection()
        instance = null
        AgentLog.i("AccessibilityService 已销毁")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // ============ 感知 ============

    fun snapshot() = Perception.snapshot(this, rootInActiveWindow)

    // ============ 动作：手势 ============

    /** 在屏幕坐标 (x,y) 单击。返回是否成功完成手势。 */
    suspend fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        return dispatch(desc)
    }

    /** 从 (x1,y1) 滑动到 (x2,y2)，duration 毫秒。 */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 220): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        return dispatch(desc)
    }

    private suspend fun dispatch(desc: GestureDescription): Boolean {
        val done = CompletableDeferred<Boolean>()
        val accepted = withContext(Dispatchers.Main) {
            dispatchGesture(
                desc,
                object : GestureResultCallback() {
                    override fun onCompleted(gesture: GestureDescription?, completed: Boolean) {
                        done.complete(completed)
                    }

                    override fun onCancelled(gesture: GestureDescription?) {
                        done.complete(false)
                    }
                },
                mainHandler
            )
        }
        if (!accepted) {
            AgentLog.w("dispatchGesture 被拒绝（服务未就绪或坐标越界）")
            return false
        }
        return withTimeoutOrNull(2000) { done.await() } ?: false
    }

    // ============ 动作：系统全局 ============

    suspend fun pressGlobal(action: Int): Boolean = withContext(Dispatchers.Main) {
        performGlobalAction(action)
    }

    // ============ 动作：按索引操作节点 ============

    suspend fun clickIndex(index: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = Perception.resolve(root, index) ?: return false
        val r = Rect()
        node.getBoundsInScreen(r)
        node.recycle()
        if (r.isEmpty) return false
        AgentLog.d("clickIndex($index) → 坐标(${r.centerX()},${r.centerY()})")
        return tap(r.centerX(), r.centerY())
    }

    suspend fun setTextIndex(index: Int, text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = Perception.resolve(root, index) ?: return false
        val ok = try {
            if (!node.isEditable) {
                AgentLog.w("节点 $index 不是可编辑控件，尝试聚焦后仍填字")
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }
            val args = android.os.Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            node.performAction(AccessibilityAction.ACTION_SET_TEXT.id, args)
        } finally {
            node.recycle()
        }
        return ok
    }

    // ============ 动作：启动 App ============

    fun openApp(spec: String): Boolean {
        val pm = packageManager
        val pkg = if (spec.contains(".") && !spec.contains(" ")) {
            spec
        } else {
            // 按 label 反查
            pm.getInstalledApplications(android.content.pm.PackageManager.MATCH_ALL)
                .firstOrNull { ai ->
                    pm.getApplicationLabel(ai).toString().contains(spec, ignoreCase = true)
                }?.packageName ?: spec
        }
        val intent = pm.getLaunchIntentForPackage(pkg) ?: run {
            AgentLog.w("找不到启动 Intent: $pkg")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            AgentLog.i("启动应用: $pkg")
            true
        } catch (t: Throwable) {
            AgentLog.e("启动应用失败: $pkg", t)
            false
        }
    }

    // ============ 感知：像素截图（可选） ============

    fun prepareProjection(data: Intent) {
        teardownProjection()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mpm.createMediaProjection(Activity.RESULT_OK, data)
        AgentLog.i("MediaProjection 已就绪（截图可用）")
    }

    fun hasProjection(): Boolean = mediaProjection != null

    fun teardownProjection() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
    }

    /** 截一张图并压缩为 PNG 字节（最长边缩到 maxDim）。无投影权限时返回 null。 */
    suspend fun captureScreenshot(maxDim: Int = 1024): ByteArray? {
        val mp = mediaProjection ?: return null
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val vd = mp.createVirtualDisplay(
            "agent-cap", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            ir.surface, null, null
        )
        delay(250)
        val img = ir.acquireLatestImage()
        if (img == null) {
            vd.release(); ir.close()
            AgentLog.w("截图失败：未取到帧")
            return null
        }
        return try {
            val plane = img.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * w
            val raw = Bitmap.createBitmap(
                w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888
            )
            raw.copyPixelsFromBuffer(buffer)
            val full = Bitmap.createBitmap(raw, 0, 0, w, h)
            raw.recycle()
            // 缩放，控制体积
            val scale = min(1.0, maxDim.toDouble() / maxOf(w, h))
            val scaled = if (scale < 1.0) {
                Bitmap.createScaledBitmap(
                    full,
                    (w * scale).toInt(), (h * scale).toInt(), true
                )
            } else full
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
            if (scaled !== full) scaled.recycle()
            AgentLog.i("截图完成 ${scaled.width}x${scaled.height}")
            out.toByteArray()
        } finally {
            img.close()
            vd.release()
            ir.close()
        }
    }
}
