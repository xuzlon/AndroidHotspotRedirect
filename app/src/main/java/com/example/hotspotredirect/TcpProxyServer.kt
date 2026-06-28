package com.example.hotspotredirect

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * TCP proxy server listening on port 5808.
 * Accepts connections and provides callbacks for handling data.
 */
class TcpProxyServer(private val listenPort: Int) {

    private var running = false
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    private var onConnection: ((Socket) -> Unit)? = null

    fun setOnConnection(handler: (Socket) -> Unit) {
        onConnection = handler
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            try {
                serverSocket = ServerSocket(listenPort)
                MainActivity.log("TCP 代理已启动，监听端口 $listenPort")
                MainActivity.log("www.koukao.cn -> 本机:$listenPort 已就绪")
                while (running) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        MainActivity.log("收到连接: ${client.inetAddress.hostAddress}:${client.port}")
                        onConnection?.invoke(client)
                        // If no custom handler, just echo/log
                        if (onConnection == null) {
                            handleDefault(client)
                        }
                    } catch (e: Exception) {
                        if (running) {
                            MainActivity.log("接受连接错误: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    MainActivity.log("TCP 代理启动失败: ${e.message}")
                }
            }
        }, "tcp-proxy")
        thread?.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { thread?.interrupt() } catch (_: Exception) {}
    }

    /**
     * Default handler: read request and respond with simple message.
     */
    private fun handleDefault(client: Socket) {
        Thread({
            try {
                client.soTimeout = 10000
                val input = client.getInputStream()
                val output = client.getOutputStream()

                // Read the HTTP request
                val buf = ByteArray(4096)
                val bytesRead = input.read(buf)
                if (bytesRead > 0) {
                    val request = String(buf, 0, bytesRead)
                    val firstLine = request.substring(0, request.indexOf("\r\n").let {
                        if (it < 0) request.length else it
                    })
                    MainActivity.log("HTTP 请求: $firstLine")

                    // Send a response
                    val response = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/html; charset=utf-8\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                        append("<html><body>")
                        append("<h1>热点转发代理</h1>")
                        append("<p>请求已转发到本机 5808 端口</p>")
                        append("<p><small>目标: www.koukao.cn -> 本机</small></p>")
                        append("</body></html>")
                    }
                    output.write(response.toByteArray())
                    output.flush()
                }
            } catch (e: Exception) {
                // Client disconnected or timeout - normal
            } finally {
                try { client.close() } catch (_: Exception) {}
            }
        }, "tcp-handler").start()
    }

    /**
     * Create a relay between a client connection and another socket.
     */
    fun relayConnection(client: Socket, targetHost: String, targetPort: Int) {
        Thread({
            try {
                val target = Socket(targetHost, targetPort)
                client.soTimeout = 30000
                target.soTimeout = 30000

                val clientInput = client.getInputStream()
                val clientOutput = client.getOutputStream()
                val targetInput = target.getInputStream()
                val targetOutput = target.getOutputStream()

                // Relay client -> target
                val t1 = Thread({
                    relayStream(clientInput, targetOutput)
                }, "relay-c2t")
                t1.start()

                // Relay target -> client
                val t2 = Thread({
                    relayStream(targetInput, clientOutput)
                }, "relay-t2c")
                t2.start()

                t1.join(30000)
                t2.join(30000)
            } catch (e: Exception) {
                MainActivity.log("中继错误: ${e.message}")
            } finally {
                try { client.close() } catch (_: Exception) {}
            }
        }, "tcp-relay").start()
    }

    private fun relayStream(input: InputStream, output: OutputStream) {
        try {
            val buf = ByteArray(8192)
            var len: Int
            while (input.read(buf).also { len = it } >= 0) {
                output.write(buf, 0, len)
                output.flush()
            }
        } catch (_: Exception) {}
    }
}