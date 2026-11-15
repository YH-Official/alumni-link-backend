package com.zxce.ai.interceptor

import kotlinx.coroutines.*
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * 网络流量统计过滤器。
 * 实时统计入站和出站流量的字节数与请求数，每秒更新一次统计数据。
 * 可通过companion object的属性获取当前流量统计值。
 *
 * @param order 过滤器的排序值
 */
abstract class NetworkTrafficFilter(order: Int) : AbstractWebFilter(order) {

    companion object {
        /** 当前入站流量字节数（每秒更新） */
        val inboundBytes: Long get() = mLastInboundBytes
        private var mInboundBytes = AtomicLong(0L)
        private var mLastInboundBytes = 0L
        /** 当前入站请求数（每秒更新） */
        val inboundCount: Int get() = mLastInboundCount
        private var mInboundCount = AtomicInteger(0)
        private var mLastInboundCount = 0
        /** 当前出站流量字节数（每秒更新） */
        val outboundBytes: Long get() = mLastOutboundBytes
        private var mOutboundBytes = AtomicLong(0L)
        private var mLastOutboundBytes = 0L
        /** 当前出站请求数（每秒更新） */
        val outboundCount: Int get() = mLastOutboundCount
        private var mOutboundCount = AtomicInteger(0)
        private var mLastOutboundCount = 0
        private fun countInBound(count: Int) {
            mInboundBytes.addAndGet(count.toLong())
            mInboundCount.incrementAndGet()
        }

        private fun countOutBound(count: Int) {
            mOutboundBytes.addAndGet(count.toLong())
            mOutboundCount.incrementAndGet()
        }

        init {
            CoroutineScope(Dispatchers.Default).launch {
                while (isActive) {
                    mLastInboundBytes = mInboundBytes.get()
                    mInboundBytes.set(0)
                    mLastInboundCount = mInboundCount.get()
                    mInboundCount.set(0)

                    mLastOutboundBytes = mOutboundBytes.get()
                    mOutboundBytes.set(0)
                    mLastOutboundCount = mOutboundCount.get()
                    mOutboundCount.set(0)
                    delay(1.seconds)
                }
            }
        }
    }

    /**
     * 执行流量统计过滤逻辑。
     * 通过包装请求和响应来统计流量数据。
     *
     * @param exchange 服务器Web交换对象
     * @param chain 过滤器链
     * @return Mono<Void> 响应式结果
     */
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val exchange1 = exchange.mutate()
            .request(Request(exchange.request))
            .response(Response(exchange.response))
            .build()
        return chain.filter(exchange1)
    }

    /**
     * 装饰HTTP请求的内部类，统计入站请求体的字节数。
     */
    inner class Request(delegate: ServerHttpRequest) : ServerHttpRequestDecorator(delegate) {
        /**
         * 获取请求体Flux，同时累计入站流量统计。
         *
         * @return 原始请求体Flux
         */
        override fun getBody(): Flux<DataBuffer> {
            return super.getBody().map { countInBound(it.capacity());it }
        }
    }

    /**
     * 装饰HTTP响应的内部类，统计出站响应体的字节数。
     */
    inner class Response(delegate: ServerHttpResponse) : ServerHttpResponseDecorator(delegate) {
        /**
         * 写入响应体，同时累计出站流量统计。
         *
         * @param body 响应体Publisher
         * @return Mono<Void> 响应式结果
         */
        override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
            val body2 = when (body) {
                is Mono -> body.map { countOutBound(it.capacity());it }
                is Flux -> body.map { countOutBound(it.capacity());it }
                else -> throw NotImplementedError("不支持的Publisher类型")
            }
            return super.writeWith(body2)
        }
    }
}