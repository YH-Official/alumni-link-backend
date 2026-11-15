package com.zxce.ai.interceptor

import com.zxce.ai.annotation.JwtIgnore
import com.zxce.ai.manager.SessionManager
import com.zxce.ai.util.ErrorResponseException
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.http.HttpStatus
import org.springframework.web.method.HandlerMethod
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Mono

/**
 * JWT认证过滤器，基于Token对API请求进行身份验证。
 * 支持通过注解@JwtIgnore标记免认证的接口，以及配置路径白名单。
 * 对于未认证的请求，返回401未授权状态码。
 *
 * @param order 过滤器的排序值
 */
abstract class JwtWebFilter(order: Int) : AbstractWebFilter(order) {
    companion object {
        @JvmStatic
        val logger = LoggerFactory.getLogger(JwtWebFilter::class.java)!!
        private val pathPatternParser = PathPatternParser()
        private val apiPattern = pathPatternParser.parse("/api/**")

        /** 登录会话的绝对有效期：30 天 */
        private const val SESSION_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000

        // 全路径匹配
        private val ignore = listOf(
            "/api/sse",
            "/api/login",
            "/api/captcha",
            "/api/verify",
            "/api/register",
            "/api/user/create",
            "/api/user/updatePassword",
            "/api/user/avatar",
            "/api/client/",
            "/api/resources/checkFiles"
        )

        // 起始匹配
        private val ignoreMatches = listOf(
            "/api/oss/show/",
            "/api/oss/get/",
            "/api/user/avatar/get/",
            "/api/user/background/get/",
            "/api/alumni/post/list",
            "/api/alumni/post/detail",
            "/api/alumni/forum/post/detail",
            "/api/alumni/forum/post/list",
            "/api/alumni/forum/post/search",
            "/api/alumni/forum/comment/list",
            "/api/alumni/forum/comment/replies",
            "/api/alumni/forum/like/check",
            "/api/alumni/forum/share/list",
            "/api/alumni/forum/category/list",
            "/api/alumni/job/list",
            "/api/alumni/job/detail",
            "/api/alumni/job/company/list",
            "/api/alumni/job/company/detail"
        )
    }

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    lateinit var requestMapping: RequestMappingHandlerMapping

    @Autowired
    lateinit var sessionManager: SessionManager

    /**
     * 执行JWT认证过滤逻辑。
     * 对/api路径下的请求进行身份验证，检查JwtIgnore注解和白名单路径，
     * 未认证则抛出401错误。
     *
     * @param exchange 服务器Web交换对象，包含请求和响应信息
     * @param chain 过滤器链，用于传递请求到下一个过滤器
     * @return Mono<Void> 响应式结果
     */
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain) = mono {
        val path = exchange.request.path.pathWithinApplication()
        var pathStr = path.value()
        if (pathStr.contains('?')) pathStr = pathStr.split('?', limit = 2)[0]
        if (apiPattern.matches(path)) {
            val method = requestMapping.getHandler(exchange).awaitSingleOrNull() as? HandlerMethod
            var isAnnotateIgnore = false
            method?.let {
                val fClass = AnnotationUtils.findAnnotation(method.beanType, JwtIgnore::class.java)?.value
                val fMethod = AnnotationUtils.findAnnotation(method.method, JwtIgnore::class.java)?.value
                isAnnotateIgnore = when {
                    fClass == true && fMethod == true -> true
                    fClass == true && fMethod == null -> true
                    fClass == null && fMethod == true -> true
                    else -> false
                }
            }

            if (isAnnotateIgnore) {
                return@mono chain.filter(exchange).awaitSingleOrNull()
            } else if (ignore.contains(pathStr)) {
                return@mono chain.filter(exchange).awaitSingleOrNull()
            } else if (ignoreMatches.any { pathStr.startsWith(it) }) {
                return@mono chain.filter(exchange).awaitSingleOrNull()
            } else {
                val session = sessionManager.mySession(exchange)
                if (session == null) {
                    throw ErrorResponseException(HttpStatus.UNAUTHORIZED, "身份认证失败，请重新登录")
                }
                val createdAt = (session["session:createdAt"] as? Number)?.toLong()
                if (createdAt != null && System.currentTimeMillis() - createdAt > SESSION_MAX_AGE_MILLIS) {
                    session["sessionId"]?.toString()?.let { sessionManager.remove(it) }
                    throw ErrorResponseException(HttpStatus.UNAUTHORIZED, "登录状态已过期，请重新登录")
                }
                return@mono chain.filter(exchange).awaitSingleOrNull()
            }
        } else {
            return@mono chain.filter(exchange).awaitSingleOrNull()
        }
    }.then()
}
