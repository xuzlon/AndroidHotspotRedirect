package com.example.hotspotredirect

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var startButton: Button
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private var instance: MainActivity? = null
        private var logLines = mutableListOf<String>()

        fun log(msg: String) {
            synchronized(logLines) {
                logLines.add(msg)
                if (logLines.size > 500) logLines.removeAt(0)
            }
            instance?.runOnUiThread {
                synchronized(logLines) {
                    instance?.logText?.text = logLines.joinToString("\n")
                }
            }
        }

        fun updateStatus(status: String) {
            instance?.runOnUiThread {
                instance?.statusText?.text = "状态：$status"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        instance = this

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        startButton = findViewById(R.id.startButton)

        // Show any existing logs
        if (logLines.isNotEmpty()) {
            logText.text = logLines.joinToString("\n")
        }

        startButton.setOnClickListener {
            if (DnsInterceptorService.isRunning) {
                stopService(Intent(this, DnsInterceptorService::class.java))
                startButton.text = "启动拦截"
                updateStatus("已停止")
            } else {
                log("正在启动服务...")
                val intent = Intent(this, DnsInterceptorService::class.java)
                startForegroundService(intent)
                startButton.text = "停止拦截"
                updateStatus("启动中...")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}