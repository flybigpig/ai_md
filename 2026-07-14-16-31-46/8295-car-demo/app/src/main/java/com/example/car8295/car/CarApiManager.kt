package com.example.car8295.car

import android.car.Car
import android.car.CarNotConnectedException
import android.car.hardware.property.CarPropertyManager
import android.car.hardware.property.CarPropertyValue
import android.content.Context
import android.util.Log

/**
 * Car API 封装：连接 CarService，读取/订阅车辆信号。
 *
 * 要点：
 *  - Car.createCar 创建后必须 connect()，连接是异步的，getCarManager 要在 onConnected 之后调用。
 *  - 车速/挡位等权限是 signature/system 级，普通应用会抛 SecurityException，这里统一吞掉并返回 null。
 *  - 回调在 CarService 的 Binder 线程，UI 更新需切主线程（调用方用 runOnUiThread）。
 */
class CarApiManager(context: Context) {

    private val car: Car = Car.createCar(context)
    private var propMgr: CarPropertyManager? = null
    private val speedCallbacks = mutableListOf<(Float) -> Unit>()

    fun connect() {
        car.connect(object : Car.CarConnectionCallback() {
            override fun onConnected(car: Car) {
                propMgr = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            }

            override fun onDisconnected(car: Car) {
                propMgr = null
            }
        })
    }

    fun disconnect() {
        if (car.isConnected) car.disconnect()
    }

    /** 读车速：无权限/未连接返回 null */
    fun getSpeed(): Float? = try {
        propMgr?.getFloatProperty(VehicleSignals.SPEED, 0)
    } catch (e: SecurityException) {
        Log.w(TAG, "无 CAR_SPEED 权限: ${e.message}")
        null
    } catch (e: CarNotConnectedException) {
        Log.w(TAG, "CarService 未连接")
        null
    }

    fun subscribeSpeed(onSpeed: (Float) -> Unit) {
        if (!speedCallbacks.contains(onSpeed)) speedCallbacks.add(onSpeed)
        propMgr?.registerCallback(
            speedEventCallback,
            VehicleSignals.SPEED,
            CarPropertyManager.SENSOR_RATE_NORMAL
        )
    }

    fun unsubscribeSpeed(onSpeed: (Float) -> Unit) {
        speedCallbacks.remove(onSpeed)
        if (speedCallbacks.isEmpty()) propMgr?.unregisterCallback(speedEventCallback)
    }

    private val speedEventCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val v = value.value as? Float ?: return
            speedCallbacks.forEach { it(v) }
        }

        override fun onErrorEvent(propId: Int, zone: Int) {
            Log.w(TAG, "车辆信号错误 propId=$propId zone=$zone")
        }
    }

    companion object {
        private const val TAG = "CarApiManager"
    }
}
