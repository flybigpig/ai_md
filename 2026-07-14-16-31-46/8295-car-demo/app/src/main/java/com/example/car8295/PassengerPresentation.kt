package com.example.car8295

import android.app.Presentation
import android.content.Context
import android.hardware.display.Display
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.example.car8295.databinding.ScreenPassengerBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PassengerPresentation(context: Context, display: Display) :
    Presentation(context, display) {

    private lateinit var binding: ScreenPassengerBinding
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            binding.tvClock.text = fmt.format(Date())
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ScreenPassengerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        handler.post(tick)
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        super.onStop()
    }
}
