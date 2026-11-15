package com.zxce.ai.util.spring

import com.zxce.ai.manager.EventSourceManager
import com.zxce.ai.util.ebean.toUuid
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.session.WebExchangeContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.reactive.BindingContext
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import kotlin.reflect.KClass

@Component
class ContextArgumentResolver : HandlerMethodArgumentResolver {

    @Autowired
    lateinit var eventSourceManager: EventSourceManager

    override fun supportsParameter(parameter: MethodParameter): Boolean {
        if (Context::class assi parameter) return true
        if (EventSource::class assi parameter) return true
        return false
    }

    override fun resolveArgument(
        parameter: MethodParameter,
        bindingContext: BindingContext,
        exchange: ServerWebExchange
    ): Mono<Any> {
        return when {
            Context::class assi parameter -> Mono.just(WebExchangeContext(exchange))
            EventSource::class assi parameter -> {
                val id = exchange.request.headers.getFirst("event-source-id")!!.toUuid()
                val eventSource = eventSourceManager.getEventSource(id)!!
                Mono.just(eventSource)
            }

            else -> throw IllegalStateException("不支持")
        }
    }

    private infix fun KClass<*>.assi(other: MethodParameter): Boolean {
        return this.java.isAssignableFrom(other.parameterType)
    }
}