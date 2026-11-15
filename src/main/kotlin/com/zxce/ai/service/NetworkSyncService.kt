package com.zxce.ai.service

import com.zxce.ai.config.SConfig
import com.zxce.ai.event.system.ServerConnectedEvent
import com.zxce.ai.manager.NetworkSyncManager
import com.zxce.ai.util.isClient
import com.zxce.ai.util.property.ComputedProperty
import com.zxce.ai.util.property.MutableProperty
import com.zxce.ai.util.property.TriggerProperty
import kotlinx.coroutines.*
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import java.net.ConnectException
import java.net.URI
import kotlin.time.Duration.Companion.seconds

@Service
class NetworkSyncService {

    private val logger = LoggerFactory.getLogger(NetworkSyncService::class.java)!!

    @Autowired
    private lateinit var manager: NetworkSyncManager

    @Autowired
    private lateinit var publisher: ApplicationEventPublisher

    final var serverSocketA: WebSocket? by MutableProperty(null)
        private set

    final var serverSocketB: WebSocket? by MutableProperty(null)
        private set

    private var connectJob: Job? by MutableProperty(null)

    private var mSockets: MutableList<WebSocket> = mutableListOf()
    val sockets by TriggerProperty { mSockets.toList() }
    val server by SConfig.host.serviceParent::selected

    val status by ComputedProperty(::serverSocketA, ::serverSocketB, ::connectJob) {
        if (serverSocketA != null || serverSocketB != null) {
            publisher.publishEvent(ServerConnectedEvent(this))
            return@ComputedProperty Status.CONNECTED
        }
        if (connectJob != null) return@ComputedProperty Status.CONNECTING
        return@ComputedProperty Status.DISCONNECTED
    }

    /**
     * 处理WebSocket连接成功事件
     *
     * 将新连接的WebSocket添加到列表中，并触发属性更新通知。
     *
     * @param ws 已连接的WebSocket实例
     */
    private fun onConnected(ws: WebSocket) {
        logger.info("WebSocket connected")
        synchronized(mSockets) {
            mSockets.add(ws)
        }
        TriggerProperty.trigger(::sockets)
    }

    /**
     * 处理WebSocket断开连接事件
     *
     * 将断开的WebSocket从列表中移除，并触发属性更新通知。
     *
     * @param ws 已断开的WebSocket实例
     * @param status 断开连接的状态信息
     */
    private fun onDisconnected(ws: WebSocket, status: CloseStatus) {
        logger.info("WebSocket disconnected: $status")
        synchronized(mSockets) {
            mSockets.remove(ws)
        }
        TriggerProperty.trigger(::sockets)
    }

    /**
     * 处理接收到的WebSocket消息
     *
     * 将消息按换行符分割，调用 NetworkSyncManager 进行网络同步处理。
     * 长度不足2的消息将被忽略。
     *
     * @param ws 发送消息的WebSocket实例
     * @param ms 接收到的WebSocket消息
     */
    private fun handleMessage(ws: WebSocket, ms: WebSocketMessage) {
        var payloads: List<String>? = null
        try {
            val txt = ms.payloadAsText
            payloads = txt.split("\n")
            // 拦截 ping 消息和其他长度不足 2 的消息
            if (payloads.size < 2) return

            logger.debug("REMOTE: ${txt.replace("\n", ", ")}")
            manager.onNetworkSync(payloads[0], payloads[1], *payloads.drop(2).toTypedArray())
        } catch (e: Throwable) {
            logger.warn("网络同步过程中发生异常，同步信息为：${payloads?.joinToString(", ")}", e)
        }
    }

    /**
     * 连接到远程服务器
     *
     * 仅在客户端模式下可用。启动后台协程循环，通过双WebSocket交错连接保证消息稳定性。
     * 连接失败时自动重试，服务器断开后每秒重连。
     *
     * @param server 可选的服务器地址，为null时使用默认配置的服务器
     * @throws IllegalStateException 当非客户端模式或未选择服务器时抛出
     */
    fun connect(server: String? = null) {
        if (!isClient) throw IllegalStateException("只有客户端模式才可以连接服务器！")
        var selected by SConfig.host.serviceParent::selected
        var records by SConfig.host.serviceParent::records

        if (server != null) {
            selected = server
            if (server !in records) {
                records = records + server
            }
        }
        selected ?: throw IllegalStateException("未选择服务器")
        synchronized(this) {
            if (connectJob != null) return
            val job = Job()
            connectJob = job
            CoroutineScope(job + Dispatchers.IO).launch {
                while (true) {
                    try {
                        doConnect()
                    } catch (e: Error) {
                        logger.error(e.message, e)
                        disconnect()
                    } catch (e: Throwable) {
                        if (e.cause is ConnectException) {
                            logger.warn(e.message)
                        } else {
                            logger.error(e.message, e)
                        }
                    } finally {
                        serverSocketA = null
                        delay(1000)
                    }
                }
            }
        }
    }

    /**
     * 通过双WebSocket交错连接保证消息的连接稳定性
     *
     * 先建立第一个WebSocket连接，延迟30秒后建立第二个连接，形成主备冗余。
     * 任一连接断开时，另一个仍可维持通信。
     */
    private suspend fun doConnect() {
        server ?: throw IllegalStateException("未选择服务器")
        val jobs = mutableListOf<Job>()
        coroutineScope {
            jobs += doConnect0(this) { serverSocketA = it }
            delay(30.seconds)
            jobs += doConnect0(this) { serverSocketB = it }
        }
        jobs.joinAll()
    }

    /**
     * 建立单个WebSocket连接的协程
     *
     * 在指定的协程作用域内循环尝试连接，每次连接超时为60秒。
     * 连接成功后通过回调通知外部，连接断开后自动重试。
     *
     * @param scope 协程作用域，用于启动连接任务
     * @param callback 连接成功后的回调函数，接收WebSocket实例
     * @return 连接任务的Job对象
     */
    private fun doConnect0(scope: CoroutineScope, callback: (WebSocket) -> Unit): Job {
        return scope.launch {
            while (isActive) {
                try {
                    // 连接最大60秒
                    withTimeout(60.seconds) {
                        val client = ReactorNettyWebSocketClient()
                        client.execute(URI("ws://${server}/api/client/")) {
                            mono {
                                val (ws, job) = handleWebSocketSession(it)
                                callback(ws)
                                job.join()
                            }.then()
                        }.awaitSingleOrNull()
                    }
                } catch (_: TimeoutCancellationException) {
                }
            }
        }
    }

    /**
     * 断开与远程服务器的连接
     *
     * 取消连接任务，关闭所有活跃的WebSocket连接，并清除相关引用。
     */
    fun disconnect() {
        synchronized(this) {
            connectJob?.cancel()
            connectJob = null
            serverSocketA?.session?.close()?.block()
            serverSocketA = null
            serverSocketB?.session?.close()?.block()
            serverSocketB = null
        }
    }

    /**
     * 处理WebSocket会话的生命周期
     *
     * 创建WebSocket封装实例，启动心跳检测协程（每2秒发送ping，超过5秒无响应则关闭连接），
     * 处理消息收发，在会话关闭时触发断开事件。
     *
     * @param ws WebSocket会话对象
     * @return 包含WebSocket封装实例和关联Job的配对
     */
    suspend fun handleWebSocketSession(ws: WebSocketSession): Pair<WebSocket, Job> {
        val job = Job()
        var closed = false
        var slink: FluxSink<WebSocketMessage>? = null
        val sendFlux: Flux<WebSocketMessage> = Flux.create {
            slink = it
        }
        val handler = WebSocket(ws) { slink!! }
        onConnected(handler)

        var listUpdate = System.currentTimeMillis()
        CoroutineScope(job + Dispatchers.IO).launch {
            while (!closed) {
                delay(2.seconds)
                try {
                    if (listUpdate < System.currentTimeMillis() - 5_000) {
                        ws.close(CloseStatus.create(1000, "TIMED_OUT")).subscribe()
                        job.cancel()
                        return@launch
                    }
                    handler.ping()
                } catch (e: Exception) {
                    logger.warn("An exception occurred during the Ping test", e)
                }
            }
        }


        logger.info("WebSocket Connect ${System.currentTimeMillis()}")

        val job1 = ws.send(sendFlux)
        val job2 = ws.receive().map {
            listUpdate = System.currentTimeMillis()
            handleMessage(handler, it)
        }

        val mono = Flux.zip(job1, job2).then().doFinally {
            logger.info("WebSocket Close ${System.currentTimeMillis()}")
            closed = true
            @Suppress("CallingSubscribeInNonBlockingScope")
            ws.closeStatus().subscribe {
                onDisconnected(handler, it)
            }
        }

        CoroutineScope(job + Dispatchers.IO).launch {
            mono.awaitSingleOrNull()
        }

        return handler to job
    }

    /**
     * 应用启动时自动连接服务器
     *
     * 在应用启动事件触发时，如果是客户端模式且已配置服务器，则自动发起连接。
     * 如果是客户端模式但未配置服务器，则记录错误日志。
     */
    @EventListener(ApplicationStartedEvent::class)
    private fun launch() {
        if (isClient && server != null) {
            connect()
        } else if (isClient) {
            logger.error("未找到可用服务器，请进行配置")
        }
    }

    enum class Status {
        CONNECTED,
        CONNECTING,
        DISCONNECTED,
    }

    /**
     * WebSocket封装类，提供线程安全的消息发送和心跳检测
     *
     * @param session 原始WebSocket会话
     * @param slink 获取FluxSink的延迟函数
     */
    class WebSocket(val session: WebSocketSession, slink: () -> FluxSink<WebSocketMessage>) {
        private val slinkGetter = slink
        private var mSlink: FluxSink<WebSocketMessage>? = null

        private val slink: FluxSink<WebSocketMessage>
            get() {
                if (mSlink == null) mSlink = slinkGetter()
                return mSlink!!
            }

        /**
         * 发送文本消息
         *
         * @param string 要发送的文本内容
         */
        fun send(string: String) {
            synchronized(slink) {
                slink.next(session.textMessage(string))
            }
        }

        /**
         * 发送心跳ping消息，用于保持连接活跃
         */
        fun ping() {
            synchronized(slink) {
                slink.next(
                    session.pingMessage {
                        it.wrap("ping".toByteArray())
                    }
                )
            }
        }
    }
}