package com.example.car8295.car

import android.car.VehiclePropertyIds

/**
 * 常用车辆属性 ID 封装。
 * 完整列表见 android.car.VehiclePropertyIds。
 * 不同车型支持的属性差异很大，先确认车厂信号矩阵再读。
 */
object VehicleSignals {
    val SPEED = VehiclePropertyIds.PERF_VEHICLE_SPEED
    val GEAR = VehiclePropertyIds.GEAR_SELECTION
    val EV_BATTERY = VehiclePropertyIds.EV_BATTERY_LEVEL
    val OUTSIDE_TEMP = VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE
}
