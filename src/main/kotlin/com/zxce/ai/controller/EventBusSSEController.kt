package com.zxce.ai.controller

import com.zxce.ai.service.EventBusSSEService
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.session.IWebExchangeContext
import com.zxce.ai.util.session.SSEContext
import com.zxce.ai.util.spring.EventSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.util.*

/**
 * SSE事件总线控制器，提供服务端事件推送连接和用户注册接口。
 */
@RestController
@RequestMapping("/api")
class EventBusSSEController {

    @Autowired
    lateinit var service: EventBusSSEService

    /**
     * 建立SSE连接，客户端通过此接口接收服务端推送的事件消息。
     *
     * @param ctx 请求上下文
     * @param source 事件源对象，用于向客户端推送事件
     * @since 1.0.0
     */
    @PostMapping("/sse")
    suspend fun connect(ctx: Context, source: EventSource) {
        val ctx1 = object : SSEContext(ctx), IWebExchangeContext {
            override val exchange: ServerWebExchange = (ctx as IWebExchangeContext).exchange
        }
        service.connect(ctx1, source)
    }

    data class RegisterUserFn(val sseId: UUID)

    /**
     * 注册用户到SSE连接，将用户与SSE会话关联。
     *
     * @param ctx 请求上下文
     * @param params 注册请求参数，包含SSE会话ID
     * @return 响应实体，包含注册结果的字节数据
     * @since 1.0.0
     */
    @PostMapping("/sse/registry")
    suspend fun registerUser(ctx: Context, @RequestBody params: RegisterUserFn): ResponseEntity<ByteArray> {
        return service.registerUser(ctx, params.sseId)
    }
}