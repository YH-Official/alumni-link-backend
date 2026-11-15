package com.zxce.ai.manager

import com.zxce.ai.util.ebean.toUuid
import com.zxce.ai.util.session.SSEContext
import com.zxce.ai.util.spring.EventSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*
import kotlin.time.Duration.Companion.seconds

/**
 * SSE事件总线管理器，负责管理所有SSE（Server-Sent Events）客户端连接。
 * 提供连接注册、查找、移除等功能，并通过心跳机制自动清理僵尸连接。
 * 实现CoroutineScope以支持协程操作。
 */
@Service
class EventBusSSEManager : CoroutineScope {

    private val logger = LoggerFactory.getLogger(EventBusSSEManager::class.java)

    override val coroutineContext get() = Dispatchers.Default

    // sseId -> controller
    private val mClients = mutableMapOf<UUID, Meta>()

    init {
        // 心跳清理协程：每 30s 遍历所有连接，尝试发送 ping，异常或已关闭则移除。
        // 防止客户端断开后 onClose 未触发导致的僵尸连接堆积。
        launch {
            while (isActive) {
                delay(30.seconds)
                heartbeat()
            }
        }
    }

    private fun heartbeat() {
        val snapshot = synchronized(mClients) { mClients.toMap() }
        if (snapshot.isEmpty()) return
        var alive = 0
        var dead = 0
        snapshot.forEach { (sseId, meta) ->
            if (meta.client.isClosed) {
                dead++
                logger.warn("【SSE-Heartbeat】dead connection (isClosed), removing sseId={}, userId={}",
                    sseId, meta.ctx.userIdOrNull)
                remove(sseId)
                try { meta.client.close() } catch (_: Throwable) {}
                return@forEach
            }
            try {
                meta.client.send("ping", "")
                alive++
            } catch (e: Throwable) {
                dead++
                logger.warn("【SSE-Heartbeat】send ping failed, removing sseId={}, userId={}, error={}: {}",
                    sseId, meta.ctx.userIdOrNull, e::class.simpleName, e.message)
                remove(sseId)
                try { meta.client.close() } catch (_: Throwable) {}
            }
        }
        if (dead > 0) {
            logger.info("【SSE-Heartbeat】done: alive={}, dead={}, remaining={}", alive, dead, size)
        }
    }

    /**
     * 记录了所有的Socket连接信息，键：SessionId
     */
    val clients: Map<UUID, Meta> get() = mClients.toMap()

    /**
 * 根据SSE会话ID获取连接元数据。
 * @param sseId SSE会话唯一标识
 * @return 连接元数据，若不存在则返回null
 */
operator fun get(sseId: UUID): Meta? {
        return mClients[sseId]
    }

    /**
 * 根据用户UUID获取其所有SSE会话。
 * @param userId 用户UUID，可以为null
 * @return 该用户的所有SSE连接映射（键为sseId）
 */
fun getUserSessions(userId: UUID?): Map<UUID, Meta> {
        synchronized(mClients) {
            return mClients.filterValues { it.ctx.userIdOrNull == userId }
        }
    }

    /**
 * 根据用户ID字符串获取其所有SSE会话。
 * @param userId 用户ID字符串，可以为null
 * @return 该用户的所有SSE连接映射（键为sseId）
 */
fun getUserSessions(userId: String?): Map<UUID, Meta> {
        return getUserSessions(userId?.toUuid())
    }

    /**
 * 注册一个新的SSE连接元数据。
 * @param meta 连接元数据，包含SSE上下文和事件源客户端
 */
fun set(meta: Meta) {
        synchronized(mClients) {
            mClients[meta.client.sseId] = meta
        }
    }

    /**
 * 通过sseId注册连接元数据。
 * @param sseId SSE会话唯一标识
 * @param meta 连接元数据
 */
operator fun set(sseId: UUID, meta: Meta) {
        synchronized(mClients) {
            mClients[sseId] = meta
        }
    }

    /**
 * 根据SSE会话ID移除连接。
 * @param sseId 要移除的SSE会话唯一标识
 */
fun remove(sseId: UUID) {
        synchronized(mClients) {
            mClients.remove(sseId)
        }
    }

    /**
 * 根据事件源客户端移除连接。
 * @param client 要移除的事件源客户端
 */
fun remove(client: EventSource) {
        synchronized(mClients){
            mClients.remove(client.sseId)
        }
    }

    /** 当前活跃的SSE连接数量。 */
val size get() = mClients.size

    data class Meta(
        val ctx: SSEContext,
        val client: EventSource
    )
}