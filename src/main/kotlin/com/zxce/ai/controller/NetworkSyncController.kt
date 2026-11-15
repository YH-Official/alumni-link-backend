package com.zxce.ai.controller

import com.zxce.ai.service.NetworkSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import kotlin.coroutines.CoroutineContext


/**
 * 网络同步控制器，处理客户端WebSocket连接，实现实时数据同步。
 */
@RestController
@RequestMapping("/api/client")
class NetworkSyncController : CoroutineScope {

    private val logger = LoggerFactory.getLogger(NetworkSyncController::class.java)

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO

    @Autowired
    lateinit var adapter: WebSocketHandlerAdapter

    @Autowired
    lateinit var service: NetworkSyncService

    /**
     * 处理客户端WebSocket连接请求，建立WebSocket会话并处理实时数据同步。
     *
     * @param exchange 服务器Web交换对象，包含WebSocket升级请求
     * @return 空的Mono<Void>，WebSocket会话结束时完成
     * @since 1.0.0
     */
    @GetMapping("/")
    fun handleClient(exchange: ServerWebExchange): Mono<Void> {
        return adapter.webSocketService.handleRequest(exchange) { ws ->
            mono {
                val (_, job) = service.handleWebSocketSession(ws)
                try {
                    job.join()
                } finally {
                    logger.info("WebSocket disconnected Mono<Void>")
                }
            }.then()
        }
    }
}