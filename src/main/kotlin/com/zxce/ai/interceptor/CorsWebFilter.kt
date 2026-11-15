package com.zxce.ai.interceptor

import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpMethod.*
import org.springframework.http.HttpStatus
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * CORS跨域资源共享过滤器。
 * 仅对白名单内的 Origin 下发跨域头，避免任意站点携带用户凭据调用接口。
 * 白名单可通过 `CORS_ALLOWED_ORIGINS` 环境变量或 `cors.allowed-origins` 系统属性配置，
 * 多个来源用英文逗号分隔。
 *
 * @param order 过滤器的排序值
 */
abstract class CorsWebFilter(order: Int) : AbstractWebFilter(order) {

    companion object {
        private val allowedOrigins: Set<String> = run {
            val raw = System.getProperty("cors.allowed-origins")
                ?: System.getenv("CORS_ALLOWED_ORIGINS")
                ?: "http://localhost:7031,http://127.0.0.1:7031,http://localhost:8080,http://127.0.0.1:8080"
            raw.split(',')
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotEmpty() }
                .toSet()
        }

        /**
         * 判断请求来源是否在白名单内。
         *
         * @param origin 请求头 Origin，可能为空（同源或非浏览器客户端）
         * @return 命中时返回规范化后的 Origin，否则返回 null
         */
        fun resolveOrigin(origin: String?): String? {
            if (origin.isNullOrBlank()) return null
            val normalized = origin.trim().trimEnd('/')
            return if (normalized in allowedOrigins) normalized else null
        }
    }

    /**
     * 执行CORS过滤逻辑。
     * 对非OPTIONS请求在响应提交前设置跨域头，对OPTIONS预检请求直接返回ok响应。
     *
     * @param exchange 服务器Web交换对象
     * @param chain 过滤器链
     * @return Mono<Void> 响应式结果
     */
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val req = exchange.request
        val resp = exchange.response
        val origin = req.headers.origin
        val allowedOrigin = resolveOrigin(origin)

        // 没带 Origin 的是同源请求或桌面/移动客户端，直接放行
        if (origin.isNullOrBlank()) {
            return chain.filter(exchange)
        }

        if (req.method != OPTIONS) {
            if (allowedOrigin == null) {
                // 非预检的跨域请求：不下发任何跨域头，交给浏览器拦截
                return chain.filter(exchange)
            }
            resp.beforeCommit {
                Mono.fromCallable {
                    resp.headers.apply {
                        remove("Access-Control-Allow-Origin")
                        remove("Access-Control-Allow-Headers")
                        remove("Access-Control-Allow-Methods")
                        add("Vary", "Origin")

                        accessControlAllowOrigin = allowedOrigin
                        accessControlAllowCredentials = true
                        accessControlAllowHeaders = req.headers.accessControlRequestHeaders
                        accessControlAllowMethods = listOf(req.headers.accessControlRequestMethod ?: req.method)
                    }
                    Unit
                }.then()
            }
            return chain.filter(exchange)
        }

        if (allowedOrigin == null) {
            resp.statusCode = HttpStatus.FORBIDDEN
            return resp.setComplete()
        }

        resp.headers.apply {
            add("Vary", "Origin")
            accessControlAllowOrigin = allowedOrigin
            accessControlAllowCredentials = true
            accessControlAllowMethods = listOf(GET, POST, PUT, DELETE, PATCH, TRACE, HEAD)
            accessControlMaxAge = 86400
            accessControlAllowHeaders = req.headers.accessControlRequestHeaders
        }
        val mono = Mono.just(DefaultDataBufferFactory.sharedInstance.wrap("ok".toByteArray()))
        return resp.writeWith(mono)
    }
}
