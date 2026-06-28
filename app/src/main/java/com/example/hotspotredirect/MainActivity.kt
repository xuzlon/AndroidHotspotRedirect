package com.example.hotspotredirect

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var startButton: Button
    private lateinit var domainEdit: EditText
    private lateinit var ipEdit: EditText
    private lateinit var dnsPortEdit: EditText
    private lateinit var proxyPortEdit: EditText

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
                    instance?.logScrollView?.post {
                        instance?.logScrollView?.fullScroll(ScrollView.FOCUS_DOWN)
                    }
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
        logScrollView = findViewById(R.id.logScrollView)
        startButton = findViewById(R.id.startButton)
        domainEdit = findViewById(R.id.domainEdit)
        ipEdit = findViewById(R.id.ipEdit)
        dnsPortEdit = findViewById(R.id.dnsPortEdit)
        proxyPortEdit = findViewById(R.id.proxyPortEdit)

        // Load saved config
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        domainEdit.setText(prefs.getString("domain", "www.koukao.cn"))
        ipEdit.setText(prefs.getString("ip", "192.168.43.1"))
        dnsPortEdit.setText(prefs.getInt("dnsPort", 5353).toString())
        proxyPortEdit.setText(prefs.getInt("proxyPort", 5808).toString())

        // Show any existing logs
        if (logLines.isNotEmpty()) {
            logText.text = logLines.joinToString("\n")
        }

        startButton.setOnClickListener {
            if (DnsInterceptorService.isRunning) {
                stopService(Intent(this, DnsInterceptorService::class.java))
                startButton.text = "启动拦截"
                updateStatus("已停止")
                enableConfig(true)
            } else {
                // Save config
                val domain = domainEdit.text.toString().trim()
                val ip = ipEdit.text.toString().trim()
                val dnsPort = dnsPortEdit.text.toString().toIntOrNull() ?: 5353
                val proxyPort = proxyPortEdit.text.toString().toIntOrNull() ?: 5808

                prefs.edit().apply {
                    putString("domain", domain)
                    putString("ip", ip)
                    putInt("dnsPort", dnsPort)
                    putInt("proxyPort", proxyPort)
                    apply()
                }

                log("正在启动服务...")
                log("目标域名: $domain")
                log("转发地址: $ip:$proxyPort")

                val intent = Intent(this, DnsInterceptorService::class.java).apply {
                    putExtra("domain", domain)
                    putExtra("ip", ip)
                    putExtra("dnsPort", dnsPort)
                    putExtra("proxyPort", proxyPort)
                }
                startForegroundService(intent)
                startButton.text = "停止拦截"
                updateStatus("启动中...")
                enableConfig(false)
            }
        }
    }

    private fun enableConfig(enabled: Boolean) {
        domainEdit.isEnabled = enabled
        ipEdit.isEnabled = enabled
        dnsPortEdit.isEnabled = enabled
        proxyPortEdit.isEnabled = enabled
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}