package com.zxce.ai.service

import com.zxce.ai.api.SystemApi
import com.zxce.ai.event.system.SystemEvent
import com.zxce.ai.manager.EventBusSSEManager
import com.zxce.ai.manager.EventSourceManager
import com.zxce.ai.util.isClient
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.session.SSEContext
import com.zxce.ai.util.spring.EventSource
import com.zxce.ai.util.success
import kotlinx.coroutines.Job
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import java.util.*

@Service
class EventBusSSEService {

    private val logger = LoggerFactory.getLogger(EventBusSSEService::class.java)

    @Autowired
    lateinit var manager: EventBusSSEManager

    @Autowired
    lateinit var eventSourceManager: EventSourceManager

    @Autowired
    lateinit var eventBus: ApplicationEventPublisher

    /**
     * 处理用户SSE（Server-Sent Events）连接请求
     * 建立连接后发送成功消息，发布连接事件，等待连接关闭后清理资源
     *
     * @param ctx SSE上下文对象
     * @param source 事件源对象，用于发送消息和等待连接关闭
     */
    suspend fun connect(ctx: SSEContext, source: EventSource) {
        var job: Job? = null

        logger.info("【SSE】connect: sseId={}, hasSessionId={}, userId={}",
            source.sseId, ctx.hasSessionId, ctx.userIdOrNull)
        val meta = EventBusSSEManager.Meta(ctx, source)
        manager[source.sseId] = meta
        logger.info("【SSE】connect: stored, total connections={}", manager.size)
        source.send("status", "connect successful")
        eventBus.publishEvent(SSEConnectedEvent(this, meta))
        // 等待 EventSource 关闭
        source.await()
        job?.cancel()
        manager.remove(source.sseId)
        logger.info("【SSE】connect: closed, sseId={}, total connections={}", source.sseId, manager.size)
        eventBus.publishEvent(SSEDisconnectedEvent(this, meta))
    }

    /**
     * 用户登录后将已建立的SSE链接与用户信息绑定
     * 如果 EventBusSSEManager 中没有 meta（因 EventSourceWebFilter 拦截了 SSE 注册请求），
     * 则从 EventSourceManager 中获取 EventSource 并自行创建 meta
     *
     * @param ctx 上下文对象，包含当前会话信息
     * @param sseId SSE连接ID
     * @return 成功响应
     */
    suspend fun registerUser(ctx: Context, sseId: UUID): ResponseEntity<ByteArray> {
        var meta = manager[sseId]
        if (meta == null) {
            // EventBusSSEManager 中没有 meta，从 EventSourceManager 获取 EventSource 并创建
            val eventSource = eventSourceManager.getEventSource(sseId)
            if (eventSource == null) {
                logger.warn("【SSE】registerUser: sseId={} not found in EventSourceManager either, total={}", sseId, manager.size)
                return success()
            }
            val sseCtx = SSEContext(ctx)
            meta = EventBusSSEManager.Meta(sseCtx, eventSource)
            manager[sseId] = meta
            logger.info("【SSE】registerUser: created new meta for sseId={}, total={}", sseId, manager.size)

            // 连接关闭时自动清理
            eventSource.onClose += {
                manager.remove(sseId)
                logger.info("【SSE】registerUser: onClose - cleaned up meta for sseId={}, total={}", sseId, manager.size)
            }
        }
        logger.info("【SSE】registerUser: before - sseId={}, oldUserId={}, oldSessionId={}",
            sseId, meta.ctx.userIdOrNull, try { meta.ctx.sessionId } catch (_: Exception) { null })
        meta.ctx.sessionId = ctx.sessionId
        logger.info("【SSE】registerUser: after - sseId={}, newUserId={}, newSessionId={}",
            sseId, meta.ctx.userIdOrNull, try { meta.ctx.sessionId } catch (_: Exception) { null })

        if (isClient) {
            // 服务器下发的SSE-ID并不一致
            logger.info("【SSE】registerUser: forwarding to server, sseId={}, userId={}", sseId, ctx.userIdOrNull)
            SystemApi.registerSseUser(ctx, meta.client.sseId)
        } else {
            logger.info("【SSE】registerUser: OK - sseId={}, userId={}, total={}", sseId, ctx.userIdOrNull, manager.size)
        }
        return success()
    }

    /**
     * SSE连接事件基类
     * 包含会话和客户端信息，用于在事件总线中传递连接状态
     */
    sealed class SSEConnectEvent(source: Any, val meta: EventBusSSEManager.Meta) : SystemEvent(source) {
        /** 获取SSE会话上下文 */
        val session get() = meta.ctx
        /** 获取SSE客户端信息 */
        val client get() = meta.client
    }

    /** SSE连接成功事件 */
    class SSEConnectedEvent(source: Any, meta: EventBusSSEManager.Meta) : SSEConnectEvent(source, meta)
    /** SSE连接断开事件 */
    class SSEDisconnectedEvent(source: Any, meta: EventBusSSEManager.Meta) : SSEConnectEvent(source, meta)
}