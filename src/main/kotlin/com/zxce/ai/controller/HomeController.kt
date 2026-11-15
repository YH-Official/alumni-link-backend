package com.zxce.ai.controller

import com.zxce.ai.service.HomeService
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.session.IWebExchangeContext
import com.zxce.ai.VITE_DEFAULT
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.net.URI

/**
 * 首页控制器，处理首页重定向和平台资源请求。
 */
@RestController
class HomeController {

    @Autowired
    lateinit var service: HomeService

    /**
     * 首页重定向，将请求重定向到默认平台路由。
     *
     * @param resp 服务器HTTP响应对象，用于设置重定向状态码和Location头
     * @return 空的Mono<Void>，响应体为空
     * @since 1.0.0
     */
    @GetMapping("/")
    fun home(resp: ServerHttpResponse): Mono<Void> {
        resp.statusCode = HttpStatus.FOUND
        resp.headers.location = URI.create("/${VITE_DEFAULT}/")
        return resp.writeWith(Mono.empty())
    }

    private val Context.sub: String
        get() {
            this@sub as IWebExchangeContext
            return request.path.toString().split("/", limit = 3)[2]
        }

    /**
     * 处理默认平台下的子路由请求，转发到对应平台处理器。
     *
     * @param ctx 请求上下文，包含请求路径等信息
     * @return 响应实体，包含平台返回的字节数组数据
     * @since 1.0.0
     */
    @GetMapping("/${VITE_DEFAULT}/**")
    suspend fun home(ctx: Context): ResponseEntity<ByteArray> {
        return service.handlePlatform(ctx, VITE_DEFAULT, ctx.sub)
    }
}