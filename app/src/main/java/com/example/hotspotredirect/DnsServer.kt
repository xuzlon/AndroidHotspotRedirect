package com.example.hotspotredirect

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Simple DNS server that resolves a target domain to the given redirect IP.
 * Listens on the specified port (e.g., 5353).
 */
class DnsServer(
    private val listenPort: Int,
    private val redirectIp: String,
    private val targetDomain: String
) {

    private var running = false
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    private val targetLower: String = targetDomain.lowercase().trim('.')
    private val targetWww: String = if (targetLower.startsWith("www.")) targetLower else "www.$targetLower"

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            try {
                socket = DatagramSocket(listenPort)
                MainActivity.log("DNS 服务器已启动，监听端口 $listenPort")
                val buf = ByteArray(1024)
                while (running) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket?.receive(packet)
                        handleDnsQuery(packet)
                    } catch (e: Exception) {
                        if (running) {
                            MainActivity.log("DNS 接收错误: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                MainActivity.log("DNS 服务器启动失败: ${e.message}")
            }
        }, "dns-server")
        thread?.start()
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        try { thread?.interrupt() } catch (_: Exception) {}
    }

    private fun handleDnsQuery(packet: DatagramPacket) {
        val data = packet.data
        val length = packet.length
        if (length < 12) return

        try {
            // Parse DNS header
            val id = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            val flags = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            val qdcount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)

            // Check if it's a standard query (0x0100 = recursion desired)
            val isQuery = (flags and 0x8000) == 0
            if (!isQuery || qdcount == 0) return

            // Parse question section
            var pos = 12
            val nameParts = mutableListOf<String>()
            while (pos < length) {
                val len = data[pos].toInt() and 0xFF
                if (len == 0) { pos++; break }
                if (len + 1 + pos > length) return
                val sb = StringBuilder()
                for (i in 1..len) {
                    sb.append(data[pos + i].toInt().toChar())
                }
                nameParts.add(sb.toString())
                pos += len + 1
            }
            val queryName = nameParts.joinToString(".")
            val qtype = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val qclass = ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)

            if (qtype != 1 || qclass != 1) return // Only handle A records in IN class

            // Check if this matches the target domain
            val queryHost = queryName.lowercase()
            val respond = queryHost == targetLower || queryHost == targetWww

            if (respond) {
                MainActivity.log("DNS 拦截: $queryName -> $redirectIp")
                // Build DNS response with A record
                val targetIpBytes = InetAddress.getByName(redirectIp).address
                val respLen = pos + 4 + 16
                val resp = ByteArray(respLen)

                // Copy ID
                resp[0] = data[0]
                resp[1] = data[1]
                // Flags: response, authoritative, recursion desired
                resp[2] = (0x85).toByte()
                resp[3] = (0x80).toByte()
                // Questions: 1
                resp[4] = data[4]
                resp[5] = data[5]
                // Answers: 1
                resp[6] = 0
                resp[7] = 1
                // Copy question section
                System.arraycopy(data, 12, resp, 12, pos - 12 + 4)
                val answerStart = pos + 4
                // Answer: name pointer (c00c = pointer to question name)
                resp[answerStart] = (0xC0).toByte()
                resp[answerStart + 1] = (0x0C).toByte()
                // Type: A (1)
                resp[answerStart + 2] = 0
                resp[answerStart + 3] = 1
                // Class: IN (1)
                resp[answerStart + 4] = 0
                resp[answerStart + 5] = 1
                // TTL: 60 seconds
                resp[answerStart + 6] = 0
                resp[answerStart + 7] = 0
                resp[answerStart + 8] = 0
                resp[answerStart + 9] = 60
                // Data length: 4 bytes
                resp[answerStart + 10] = 0
                resp[answerStart + 11] = 4
                // IP address
                resp[answerStart + 12] = targetIpBytes[0]
                resp[answerStart + 13] = targetIpBytes[1]
                resp[answerStart + 14] = targetIpBytes[2]
                resp[answerStart + 15] = targetIpBytes[3]

                val responsePacket = DatagramPacket(
                    resp, respLen,
                    packet.address, packet.port
                )
                socket?.send(responsePacket)
            } else {
                // Forward non-target queries to upstream DNS
                forwardDnsQuery(data, length, packet)
            }
        } catch (e: Exception) {
            MainActivity.log("DNS 处理错误: ${e.message}")
        }
    }

    private fun forwardDnsQuery(queryData: ByteArray, queryLen: Int, origPacket: DatagramPacket) {
        Thread({
            try {
                val upstreamSocket = DatagramSocket()
                upstreamSocket.soTimeout = 3000
                val upstream = InetAddress.getByName("8.8.8.8")
                val queryPacket = DatagramPacket(queryData, queryLen, upstream, 53)
                upstreamSocket.send(queryPacket)

                val buf = ByteArray(1500)
                val respPacket = DatagramPacket(buf, buf.size)
                upstreamSocket.receive(respPacket)

                val responseData = ByteArray(respPacket.length)
                System.arraycopy(respPacket.data, 0, responseData, 0, respPacket.length)

                val srcPacket = DatagramPacket(
                    responseData, responseData.size,
                    origPacket.address, origPacket.port
                )
                socket?.send(srcPacket)
                upstreamSocket.close()
            } catch (e: Exception) {
                // Timeout or error - silently ignore
            }
        }, "dns-forward").start()
    }
}