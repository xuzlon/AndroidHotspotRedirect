package com.example.hotspotredirect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader

class DnsInterceptorService : Service() {

    private var dnsServer: DnsServer? = null
    private var tcpProxy: TcpProxyServer? = null
    private var iptablesApplied = false

    companion object {
        const val DNS_PORT = 5353
        const val PROXY_PORT = 5808
        const val REDIRECT_HOST = "koukao.cn"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "hotspot_redirect_channel"

        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        isRunning = true
        MainActivity.updateStatus("运行中")
        MainActivity.log("服务已启动")

        // Get the hotspot gateway IP
        val hotspotIp = getHotspotIp()

        // 1. Start DNS server
        dnsServer = DnsServer(DNS_PORT, hotspotIp).also { it.start() }

        // 2. Start TCP proxy on port 5808
        tcpProxy = TcpProxyServer(PROXY_PORT).also { it.start() }

        // 3. Apply iptables rules (requires root)
        applyIptables(hotspotIp)

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        MainActivity.updateStatus("已停止")

        // Remove iptables rules
        removeIptables()

        // Stop services
        dnsServer?.stop()
        tcpProxy?.stop()

        MainActivity.log("服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Get the hotspot gateway IP address (usually 192.168.43.1)
     */
    private fun getHotspotIp(): String {
        return try {
            val cmd = "ip route show table all | grep -E '^192\\.168\\.' | head -1"
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val line = reader.readLine()
            reader.close()
            proc.waitFor()
            if (line != null) {
                // Extract IP from "192.168.43.1/24 dev wlan0 ..."
                val parts = line.split(" ")
                if (parts.isNotEmpty()) {
                    val ipWithMask = parts[0]
                    val slashIdx = ipWithMask.indexOf('/')
                    if (slashIdx > 0) ipWithMask.substring(0, slashIdx) else ipWithMask
                } else {
                    "192.168.43.1"
                }
            } else {
                "192.168.43.1"
            }
        } catch (e: Exception) {
            MainActivity.log("获取热点 IP 失败: ${e.message}，使用默认 192.168.43.1")
            "192.168.43.1"
        }
    }

    /**
     * Execute a shell command as root (via su)
     */
    private fun execRoot(cmd: String): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val exitCode = proc.waitFor()

            // Read stderr for any error messages
            val errorReader = BufferedReader(InputStreamReader(proc.errorStream))
            var errorLine: String?
            while (errorReader.readLine().also { errorLine = it } != null) {
                MainActivity.log("iptables stderr: $errorLine")
            }
            errorReader.close()

            if (exitCode == 0) {
                MainActivity.log("执行成功: $cmd")
                true
            } else {
                MainActivity.log("执行失败(exit=$exitCode): $cmd")
                false
            }
        } catch (e: Exception) {
            MainActivity.log("Root 命令执行错误: ${e.message}")
            false
        }
    }

    /**
     * Check if root is available
     */
    private fun isRootAvailable(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val path = reader.readLine()
            reader.close()
            proc.waitFor()
            path != null && path.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Apply iptables rules to redirect hotspot traffic.
     *
     * Rules:
     * 1. Redirect DNS (UDP 53) from hotspot to our DNS server (5353)
     * 2. Redirect HTTP (TCP 80) to our proxy (5808)
     * 3. Redirect HTTPS (TCP 443) to our proxy (5808)
     */
    private fun applyIptables(hotspotIp: String) {
        if (!isRootAvailable()) {
            MainActivity.log("未检测到 Root 权限！")
            MainActivity.log("请在已 Root 的安卓设备上运行")
            MainActivity.log("")
            MainActivity.log("无 Root 时功能受限：DNS 拦截仍然工作")
            MainActivity.log("但需要手动设置连接到热点的设备的 DNS 为本机 IP")
            return
        }

        MainActivity.log("Root 权限检测通过，正在应用 iptables 规则...")

        // Detect hotspot interface
        val ifaceResult = detectHotspotInterface()
        if (ifaceResult == null) {
            MainActivity.log("未检测到热点接口，尝试使用默认接口")
        }
        val ifaceOption = ifaceResult?.let { "-i $it" } ?: ""

        // Find current iptables location
        val iptablesCmd = findIptables()

        // 1. DNS redirect: UDP/53 -> UDP/5353
        val cmds = listOf(
            // Create a new chain for our rules
            "$iptablesCmd -t nat -N HOTSPOT_REDIRECT 2>/dev/null",
            // Flush the chain if it exists
            "$iptablesCmd -t nat -F HOTSPOT_REDIRECT",
            // DNS redirect
            "$iptablesCmd -t nat -A HOTSPOT_REDIRECT -p udp --dport 53 -j REDIRECT --to-port $DNS_PORT",
            // HTTP redirect
            "$iptablesCmd -t nat -A HOTSPOT_REDIRECT -p tcp --dport 80 -j REDIRECT --to-port $PROXY_PORT",
            // HTTPS redirect
            "$iptablesCmd -t nat -A HOTSPOT_REDIRECT -p tcp --dport 443 -j REDIRECT --to-port $PROXY_PORT",
            // Apply to PREROUTING (captures hotspot client traffic)
            "$iptablesCmd -t nat -A PREROUTING -p udp --dport 53 -j HOTSPOT_REDIRECT",
            "$iptablesCmd -t nat -A PREROUTING -p tcp --dport 80 -j HOTSPOT_REDIRECT",
            "$iptablesCmd -t nat -A PREROUTING -p tcp --dport 443 -j HOTSPOT_REDIRECT"
        )

        var allSuccess = true
        for (cmd in cmds) {
            if (!execRoot(cmd)) {
                allSuccess = false
            }
        }

        if (allSuccess) {
            iptablesApplied = true
            MainActivity.log("iptables 规则已应用")
            MainActivity.log("----------------------------------------")
            MainActivity.log("热点转发已就绪")
            MainActivity.log("  www.koukao.cn -> 本机:$PROXY_PORT")
            MainActivity.log("  DNS 服务器 -> 本机:$DNS_PORT")
            MainActivity.log("  热点网关 IP: $hotspotIp")
            MainActivity.log("----------------------------------------")
        } else {
            MainActivity.log("部分 iptables 规则应用失败")
        }
    }

    /**
     * Detect the hotspot network interface name
     */
    private fun detectHotspotInterface(): String? {
        val candidates = listOf("ap0", "wlan0", "wlan1")
        for (iface in candidates) {
            try {
                val checkCmd = "ip link show $iface 2>/dev/null | head -1"
                val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", checkCmd))
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                val line = reader.readLine()
                reader.close()
                proc.waitFor()
                if (line != null && line.contains(iface)) {
                    MainActivity.log("检测到热点接口: $iface")
                    return iface
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Find the iptables binary
     */
    private fun findIptables(): String {
        val candidates = listOf(
            "/system/bin/iptables",
            "/system/xbin/iptables",
            "/data/adb/modules/busybox/iptables",
            "iptables"
        )
        for (path in candidates) {
            try {
                val checkCmd = "test -x $path && echo exists"
                val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", checkCmd))
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                val result = reader.readLine()
                reader.close()
                proc.waitFor()
                if (result == "exists") {
                    MainActivity.log("找到 iptables: $path")
                    return path
                }
            } catch (_: Exception) {}
        }
        MainActivity.log("使用默认 iptables 路径")
        return "iptables"
    }

    /**
     * Remove iptables rules
     */
    private fun removeIptables() {
        if (!iptablesApplied) return

        val iptablesCmd = findIptables()

        val cmds = listOf(
            "$iptablesCmd -t nat -D PREROUTING -p udp --dport 53 -j HOTSPOT_REDIRECT 2>/dev/null",
            "$iptablesCmd -t nat -D PREROUTING -p tcp --dport 80 -j HOTSPOT_REDIRECT 2>/dev/null",
            "$iptablesCmd -t nat -D PREROUTING -p tcp --dport 443 -j HOTSPOT_REDIRECT 2>/dev/null",
            "$iptablesCmd -t nat -F HOTSPOT_REDIRECT 2>/dev/null",
            "$iptablesCmd -t nat -X HOTSPOT_REDIRECT 2>/dev/null"
        )

        for (cmd in cmds) {
            try {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
            } catch (_: Exception) {}
        }

        iptablesApplied = false
        MainActivity.log("iptables 规则已清理")
    }

    // ---------- Notification ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "热点转发服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "热点转发后台服务通知"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("热点转发运行中")
            .setContentText("www.koukao.cn → 本机:$PROXY_PORT")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }
}