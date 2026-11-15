package com.zxce.ai.interceptor

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import com.zxce.ai.util.bundle.bundleScope
import com.zxce.ai.util.kotlin.pass
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.ErrorResponse
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * 全局错误处理过滤器。
 * 捕获请求处理过程中的异常，将其转换为统一的JSON错误响应格式。
 * 支持Ignore和Warn接口标记的异常分别忽略或仅记录警告。
 *
 * @param order 过滤器的排序值
 */
abstract class ErrorWebFilter(order: Int) : AbstractWebFilter(order) {
    /** 标记异常应被忽略，不记录日志 */
    interface Ignore
    /** 标记异常仅记录警告日志 */
    interface Warn
    companion object {
        /**
         * 对外暴露的错误处理方法，将异常转换为标准错误响应。
         *
         * @param response HTTP响应对象
         * @param e 捕获的异常
         */
        suspend fun makeError(response: ServerHttpResponse, e: Throwable) {
            return makeError0(response, e)
        }
    }

    /**
     * 执行错误过滤逻辑，捕获链中的异常并进行格式化处理。
     *
     * @param exchange 服务器Web交换对象
     * @param chain 过滤器链
     * @return Mono<Void> 响应式结果
     */
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        return mono {
            try {
                chain.filter(exchange).awaitSingleOrNull()
            } catch (e: Throwable) {
                makeError0(exchange.response, e)
            }
        }.then()
    }
}

private val logger = LoggerFactory.getLogger(ErrorWebFilter::class.java)!!
private val mapper = ObjectMapper()
private val bufferFactory = DefaultDataBufferFactory()

private suspend fun makeError0(response: ServerHttpResponse, e: Throwable) {

    val code = when (e) {
        is HttpStatusCodeException -> e.statusCode
        is ErrorResponse -> e.statusCode
        // 业务异常统一映射为语义化状态码，避免全部落到 500
        is IllegalArgumentException -> HttpStatus.BAD_REQUEST
        is NullPointerException -> HttpStatus.BAD_REQUEST
        is IllegalAccessException -> HttpStatus.FORBIDDEN
        is NoSuchElementException -> HttpStatus.NOT_FOUND
        else -> null
    }
    if (code == HttpStatus.NOT_FOUND) pass
    else if (code == HttpStatus.METHOD_NOT_ALLOWED) pass
    else if (code == HttpStatus.UNAUTHORIZED) pass
    else if (code == HttpStatus.BAD_REQUEST && e is NullPointerException) logger.warn("请求参数缺失: ${e.message}")
    else if (e is ErrorWebFilter.Ignore) pass
    else if (e is ErrorWebFilter.Warn) logger.warn(e::class.qualifiedName + ": " + e.message)
    else if (e::class.qualifiedName == "kotlinx.coroutines.JobCancellationException") pass
    else logger.error(e.message, e)

    val isActive = coroutineScope { isActive }

    // 如果响应体被提交，或者连接被切断，直接返回
    if (response.isCommitted || !isActive) return

    val obj = bundleScope {
        "data" set NullNode.instance
        "msg" set e.message
        "code" set (code?.value() ?: 500)

        // 所有错误列表
        val types = mutableListOf<String>()
        var e1: Throwable? = e;
        while (e1 != null) {
            types += e1::class.java.name
            e1 = e1.cause
        }

        if (types.size == 1) "type" set types[0]
        else "type" set types
    }

    val bytes = mapper.writeValueAsBytes(obj)
    response.apply {
        headers.contentType = MediaType.APPLICATION_JSON
        headers.contentLength = bytes.size.toLong()
        headers.setCacheControl(CacheControl.noCache())
        statusCode = code ?: HttpStatus.INTERNAL_SERVER_ERROR
        writeWith(Mono.just(bufferFactory.wrap(bytes))).awaitSingleOrNull()
    }
}