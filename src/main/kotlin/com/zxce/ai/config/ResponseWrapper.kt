package com.zxce.ai.config

import com.zxce.ai.api.ApiResponseEntity
import com.zxce.ai.interceptor.AbstractWebFilter
import com.fasterxml.jackson.databind.ObjectMapper
import org.reactivestreams.Publisher
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * 响应包装过滤器。
 * 对非/api/路径的响应进行统一包装，将响应体包装为标准ApiResponseEntity格式
 * （包含data、code、msg字段）。对于/api/路径的请求直接跳过。
 *
 * @param objectMapper 用于JSON序列化的ObjectMapper
 */
@Component
class ResponseWrapper(
    private val objectMapper: ObjectMapper
) : AbstractWebFilter(100) {

    /**
     * 执行响应包装过滤逻辑。
     * 对非/api/路径的响应体进行JSON包装，统一响应格式。
     *
     * @param exchange 服务器Web交换对象
     * @param chain 过滤器链
     * @return Mono<Void> 响应式结果
     */
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val url = exchange.request.path.pathWithinApplication().toString()
        // ReturnWebFilter 已经处理了 /api/ 路径的包装，这里跳过避免双重包装
        if (url.startsWith("/api/")) {
            return chain.filter(exchange)
        }
        val originalResponse = exchange.response
        val bufferFactory = originalResponse.bufferFactory()

        val responseDecorator = object : ServerHttpResponseDecorator(originalResponse) {
            override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
                // 这个包装只服务于接口返回值。HTML 页面（比如 /admin/db）被塞进 data 字段就废了，
                // 所以按内容类型放行，行为和没装这个过滤器时完全一致。
                val contentType = headers.contentType
                if (contentType != null && contentType.isCompatibleWith(MediaType.TEXT_HTML)) {
                    return super.writeWith(body)
                }
                return Flux.from(body).collectList().flatMap { buffers ->
                    val bytes = buffers.flatMap {
                        val byteArray = ByteArray(it.readableByteCount())
                        it.read(byteArray)
                        byteArray.toList()
                    }.toByteArray()

                    val responseBody = String(bytes, Charsets.UTF_8)

                    val wrappedBody: DataBuffer = try {
                        val data = parseJson(responseBody)
                        val wrapped = ApiResponseEntity(data = data, code = 200, msg = "")
                        val jsonBytes = objectMapper.writeValueAsBytes(wrapped)
                        bufferFactory.wrap(jsonBytes)
                    } catch (e: Exception) {
                        bufferFactory.wrap(responseBody.toByteArray())
                    }

                    originalResponse.headers.contentType = MediaType.APPLICATION_JSON
                    originalResponse.headers.contentLength = wrappedBody.readableByteCount().toLong()
                    originalResponse.writeWith(Mono.just(wrappedBody))
                }
            }

            private fun parseJson(json: String): Any? {
                return try {
                    objectMapper.readValue(json, Any::class.java)
                } catch (_: Exception) {
                    json
                }
            }
        }

        return chain.filter(exchange.mutate().response(responseDecorator).build())
    }
}