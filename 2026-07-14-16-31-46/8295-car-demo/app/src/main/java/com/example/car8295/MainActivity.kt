package com.example.car8295

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.car8295.car.CarApiManager
import com.example.car8295.databinding.ActivityMainBinding
import com.example.car8295.scan.CameraXScanActivity

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var carApi: CarApiManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // android.car 需连接 CarService，连接为异步
        carApi = CarApiManager(this).also { it.connect() }

        // 1) 多屏 / 响应式：副驾屏演示（单屏环境会 Toast 提示）
        binding.btnMultiScreen.setOnClickListener {
            MultiDisplayHelper.showPassengerScreen(this)
        }

        // 2) 扫码：相机 + ML Kit
        binding.btnScan.setOnClickListener {
            startActivity(Intent(this, CameraXScanActivity::class.java))
        }

        // 3) 车辆信号：读车速 + 订阅变化
        binding.btnVehicle.setOnClickListener {
            val speed = carApi.getSpeed()
            binding.tvSpeed.text = if (speed != null) "车速：$speed km/h" else "车速：无权限/不可用"
        }
        carApi.subscribeSpeed { speed ->
            runOnUiThread { binding.tvSpeed.text = "车速：$speed km/h" }
        }
    }

    override fun onDestroy() {
        carApi.disconnect()
        super.onDestroy()
    }
}
