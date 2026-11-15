package com.zxce.ai.interceptor

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.zxce.ai.manager.EventSourceManager
import com.zxce.ai.util.bundle.bundleScope
import com.zxce.ai.util.ebean.toUuid
import com.zxce.ai.util.event.Event
import com.zxce.ai.util.event.EventHandle
import com.zxce.ai.util.spring.EventSource
import kotlinx.coroutines.*
import kotlinx.coroutines.reactor.asMono
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.apache.commons.logging.LogFactory
import org.reactivestreams.Publisher
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import reactor.core.publisher.Mono
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.seconds

/**
 * SSE(Server-Sent Events)事件源过滤器。
 * 处理SSE连接的创建、重连和消息推送。
 * 支持通过x-upgrade头标识SSE创建请求，以及通过eventSourceId参数进行连接重连。
 *
 * @param order 过滤器的排序值
 */
abstract class EventSourceWebFilter(order: Int) : AbstractWebFilter(order) {

    val logger = LogFactory.getLog(EventSourceWebFilter::class.java)

    @Autowired
    lateinit var manager: EventSourceManager

    var mapper: JsonMapper = JsonMapper().apply {
        registerModule(JavaTimeModule())
    }

    val wrapper get() = DefaultDataBufferFactory.sharedInstance

    /**
     * 执行SSE事件源过滤逻辑。
     * 处理三种情况：
     * 1. x-upgrade头为event-source时创建新的SSE连接
     * 2. 带有eventSourceId参数时重连或恢复已有SSE连接
     * 3. 其他请求正常通过过滤器链
     *
     * @param exchange 服务器Web交换对象
     * @param chain 过滤器链
     * @return Mono<Void> 响应式结果
     */
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val req = exchange.request
        if (req.headers.getFirst("x-upgrade") == "event-source") {
            return mono {
                val id = manager.createEventSource(exchange.request)
                logger.info("【SSE】POST /sse - created eventSourceId=${id}")
                val data = bundleScope {
                    "data" set id.toString()
                    "code" set 200
                }
                val bytes = mapper.writeValueAsBytes(data)
                val buffer = wrapper.wrap(bytes)
                exchange.response.headers.apply {
                    contentType = MediaType.APPLICATION_JSON
                    contentLength = bytes.size.toLong()
                }
                exchange.response.writeWith(Mono.just(buffer)).awaitSingleOrNull()
                logger.info("【SSE】POST /sse - response sent, eventSourceId=${id}")
            }.then()
        } else if ("eventSourceId" in req.queryParams) {
            val id = req.queryParams.getFirst("eventSourceId")!!.toUuid()
            return if ("last-event-id" in req.headers) {
                logger.info("【SSE】GET /sse - reconnecting SSE, eventSourceId=${id}")
                val resp = Response(exchange.response, id)
                resp.writerStatus.asMono(Dispatchers.Default).then()
            } else {
                logger.info("【SSE】GET /sse - establishing new SSE connection, eventSourceId=${id}")
                val (req1, body) = manager.getRequest(id)
                    ?: throw NoSuchElementException("no event source $id found!")
                val resp = Response(exchange.response, id, true)
                resp.writerStatus.asMono(Dispatchers.Default).then()
            }
        } else return chain.filter(exchange)
    }

    /**
     * 装饰HTTP请求的内部类，用于在请求头中添加event-source-id，
     * 并将指定数据作为请求体发送。
     */
    inner class Request(
        delegate: ServerHttpRequest,
        private val data: ByteArray,
        id: UUID,
    ) : ServerHttpRequestDecorator(delegate) {
        private val mHeaders = HttpHeaders(delegate.headers)

        init {
            mHeaders["event-source-id"] = id.toString()
        }

        /**
         * 获取包含event-source-id的请求头副本。
         *
         * @return 修改后的HTTP请求头
         */
        override fun getHeaders(): HttpHeaders {
            return mHeaders
        }

        /**
         * 获取请求体Flux，将构造时传入的数据包作为请求体。
         *
         * @return 包含请求体数据的Flux
         */
        override fun getBody(): Flux<DataBuffer> {
            return Mono.just<DataBuffer>(wrapper.wrap(data)).flux()
        }
    }

    /**
     * 装饰HTTP响应的内部类，用于管理SSE连接的生命周期。
     * 初始化时创建或恢复InnerEventSource，并通过协程管理写入状态。
     */
    inner class Response(
        delegate: ServerHttpResponse,
        id: UUID,
        create: Boolean = false
    ) : ServerHttpResponseDecorator(delegate) {
        init {
            var eventSource = (manager.getEventSource(id) as? InnerEventSource)
            if (eventSource != null) {
                eventSource.slink?.complete()
                eventSource.init(this)
            } else {
                eventSource = InnerEventSource(id)
                eventSource.init(this)
                if (create) {
                    manager.setEventSource(id, eventSource)
                } else {
                    eventSource.close()
                }
            }
        }

        lateinit var writerStatus: Job
        val dHeaders get() = delegate.headers

        /**
         * 获取响应头的独立副本，避免后续修改影响原始头部。
         *
         * @return 响应头副本
         */
        override fun getHeaders(): HttpHeaders {
            // 后续所有的Headers修改，皆不在影响前面的Headers的内容。
            val headers = HttpHeaders()
            headers.putAll(dHeaders)
            return headers
        }

        /**
         * 实际执行响应体写入的方法，通过协程等待写入完成。
         *
         * @param body 响应体Flux
         * @return Mono<Void> 响应式结果
         */
        fun writeWith0(body: Flux<out DataBuffer>): Mono<Void> {
            var result: Result<Unit>? = null
            var con: Continuation<Unit>? = null
            writerStatus = CoroutineScope(Dispatchers.Default).launch {
                result?.let {
                    if (it.isFailure) throw it.exceptionOrNull()!!
                } ?: suspendCoroutine {
                    con = it
                }
            }
            val flux = body.doOnComplete {
                result = Result.success(Unit)
                con?.resumeWith(result!!)
            }.doOnError {
                result = Result.failure(it)
                con?.resumeWith(result!!)
            }
            return super.writeWith(flux)
        }

        /**
         * 重写writeWith方法，等待SSE写入协程完成后再返回。
         *
         * @param body 响应体Publisher
         * @return Mono<Void> 响应式结果
         */
        override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
            return writerStatus.asMono(Dispatchers.Default).then()
        }
    }

    private val mInnerEventSourceId = AtomicInteger(0);

    /**
     * 消息源，向客户端发送信息的实现。
     */
    private inner class InnerEventSource(
        override val sseId: UUID
    ) : EventSource {
        private lateinit var flux: Flux<DataBuffer>
        var slink: FluxSink<DataBuffer>? = null
        private var mClosed = false
        override val isClosed: Boolean get() = mClosed

        override val onClose = EventHandle<Event>()

        fun init(response: Response) {
            if (isClosed) throw IllegalStateException("EventSource has been closed!")

            flux = Flux.create {
                // 这个回调在 response.writeWith0().subscribe() 被调用时才会执行。
                slink = it
                // 立即发送初始注释，强制 flush 响应头，确保客户端能收到响应头
                it.next(wrapper.wrap(":connected\n\n".toByteArray()))
                logger.info("【SSE】InnerEventSource - :connected sent, sseId=${sseId}")
            }
            response.dHeaders.let {
                it.contentType = MediaType.parseMediaType("text/event-stream")
                it.cacheControl = "no-cache"
                it.connection = listOf("keep-alive")
            }

            logger.info("【SSE】InnerEventSource.init - SSE stream initializing, sseId=${sseId}")

            // 连接断开（错误/取消）后自动清理，60s 宽限期内若客户端重连则跳过清理
            response.writeWith0(flux)
                .doOnError { e ->
                    logger.warn("【SSE】InnerEventSource - connection error, SSE-ID: ${sseId}")
                    logger.warn("${e::class.qualifiedName}: ${e.message}")
                    scheduleCloseAfterGrace()
                }
                .doOnCancel {
                    logger.info("【SSE】InnerEventSource - connection cancelled (client disconnect), SSE-ID: ${sseId}")
                    scheduleCloseAfterGrace()
                }
                .subscribe()

            logger.info("【SSE】InnerEventSource.init - SSE stream started, sseId=${sseId}")

            // 自动Ping消息
            CoroutineScope(Dispatchers.Default).launch {
                logger.info("【SSE】InnerEventSource - ping coroutine started, sseId=${sseId}")
                while (!mClosed) {
                    slink?.next(wrapper.wrap(":ping ${System.currentTimeMillis()}\n\n".toByteArray()))
                    delay(5.seconds)
                }
                logger.info("【SSE】InnerEventSource - ping coroutine stopped, sseId=${sseId}")
            }
        }

        /**
         * 连接断开后调度清理：立即清空 slink 停止写入，60s 后若仍未重连则标记关闭并触发 onClose。
         * 60s 宽限期允许客户端断线重连（重连时 init() 会重新赋值 slink，此时 slink != null 跳过清理）。
         */
        private fun scheduleCloseAfterGrace() {
            slink = null
            CoroutineScope(Dispatchers.Default).launch {
                delay(60.seconds)
                if (slink == null && !mClosed) {
                    mClosed = true
                    onClose fire Event(this@InnerEventSource)
                    manager.removeEventSource(sseId)
                    logger.info("【SSE】InnerEventSource - cleaned up after disconnect, sseId=${sseId}")
                }
            }
        }

        override fun send(event: String, data: Any?, retry: Int) {
            if (mClosed) throw IllegalStateException("EventSource has been closed!")
            // log.info("$event / $data")
            val id = mInnerEventSourceId.incrementAndGet()
            val dataBytes = mapper.writeValueAsString(data)
            val text = "id: ${id}\nevent: ${event}\ndata: ${dataBytes}\nretry: ${retry}\n\n"
            slink?.next(wrapper.wrap(text.toByteArray()))
        }

        override fun close() {
            if (mClosed) return
            logger.info("【SSE】InnerEventSource.close - closing SSE connection, sseId=${sseId}")
            send("close")
            slink?.complete()
            slink = null
            onClose fire Event(this)
            manager.removeEventSource(sseId)
            mClosed = true
            logger.info("【SSE】InnerEventSource.close - SSE connection closed, sseId=${sseId}")
        }
    }
}