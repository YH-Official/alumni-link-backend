package com.zxce.ai.service.system

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * 局域网自动发现服务。
 *
 * 客户端（手机 App）向局域网广播 [DISCOVER_MESSAGE]，本服务收到后向来源地址单播回复
 * [RESPONSE_PREFIX] + HTTP 端口。客户端从回复包的来源 IP 即可拼出完整的后端地址，
 * 实现"换手机/换网络无需手动填写服务器 IP"。
 *
 * 协议设计说明：
 * - 使用 UDP 广播而非 HTTP 扫描，避免遍历 254 个 IP 带来的秒级延迟
 * - 回复内容里不写死 IP，客户端以回复包的源地址为准，多网卡环境下也能取到正确的那个 IP
 */
@Component
class DiscoveryServer(
    @Value("\${server.port:7031}") private val httpPort: Int
) {

    companion object {
        private val log = LoggerFactory.getLogger(DiscoveryServer::class.java)

        /** 发现协议端口（UDP） */
        const val DISCOVERY_PORT = 7032

        /** 客户端广播的探测内容 */
        const val DISCOVER_MESSAGE = "ALUMNI_LINK_DISCOVER"

        /** 服务端回复前缀，后接 HTTP 端口 */
        const val RESPONSE_PREFIX = "ALUMNI_LINK_SERVER:"
    }

    @Volatile
    private var running = true
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    @PostConstruct
    fun start() {
        val serverSocket = runCatching { DatagramSocket(DISCOVERY_PORT) }.getOrNull()
        if (serverSocket == null) {
            log.warn("[Discovery] UDP {} 端口不可用，局域网自动发现已禁用", DISCOVERY_PORT)
            return
        }
        socket = serverSocket
        worker = thread(name = "alumni-discovery", isDaemon = true) {
            val buffer = ByteArray(256)
            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    serverSocket.receive(packet)
                    val message = String(packet.data, 0, packet.length).trim()
                    if (message == DISCOVER_MESSAGE) {
                        // 回复格式：ALUMNI_LINK_SERVER:<port>;<ip1>,<ip2>,...
                        // 带上全部本地 IPv4，由客户端逐个验证——多网卡同网段时系统可能选错回复源地址
                        val reply = "$RESPONSE_PREFIX$httpPort;${localIPv4Addresses().joinToString(",")}".toByteArray()
                        serverSocket.send(
                            DatagramPacket(reply, reply.size, packet.address, packet.port)
                        )
                        log.info(
                            "[Discovery] 响应探测 {} -> 候选地址 {}",
                            packet.address.hostAddress,
                            localIPv4Addresses().joinToString(",")
                        )
                    }
                } catch (e: Exception) {
                    if (running) log.debug("[Discovery] 接收异常: {}", e.message)
                }
            }
        }
        log.info("[Discovery] 局域网自动发现已启动，UDP {}", DISCOVERY_PORT)
    }

    /**
     * 枚举本机全部可用的 IPv4 地址（排除回环、链路本地与已关闭的接口）。
     */
    private fun localIPv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    @PreDestroy
    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        worker = null
        log.info("[Discovery] 局域网自动发现已停止")
    }
}
