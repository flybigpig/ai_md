package com.example.car8295

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import android.widget.Toast

object MultiDisplayHelper {

    /** 列出所有非默认（副驾/仪表/HUD）显示屏 */
    fun getSecondaryDisplays(context: Context): List<Display> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.displays.filter { it.displayId != Display.DEFAULT_DISPLAY }
    }

    /**
     * 方式一（推荐）：用 Presentation 在副屏渲染内容，
     * 不需要新开 Activity，从当前 Activity 直接 show()。
     */
    fun showPassengerScreen(activity: Activity) {
        val secondary = getSecondaryDisplays(activity).firstOrNull()
        if (secondary == null) {
            Toast.makeText(activity, "未检测到副屏，单屏环境", Toast.LENGTH_SHORT).show()
            return
        }
        PassengerPresentation(activity, secondary).show()
    }

    /**
     * 方式二：把独立 Activity 启动到指定 displayId。
     * 注意：部分 OEM 要求在 manifest 声明副驾屏可运行
     *（见 AndroidManifest 的 com.android.car.allowed_passenger_display）。
     */
    fun launchActivityOnDisplay(context: Context, intent: Intent, displayId: Int) {
        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        context.startActivity(intent, opts.toBundle())
    }
}
