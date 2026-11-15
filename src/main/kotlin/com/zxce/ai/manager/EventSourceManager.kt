package com.zxce.ai.manager

import com.zxce.ai.util.spring.EventSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Service
import java.util.*
import kotlin.time.Duration.Companion.seconds

/**
 * 事件源管理器，负责协调SSE事件源的创建、获取和移除。
 * 通过请求缓存机制关联POST请求与后续的GET SSE连接请求。
 */
@Service
class EventSourceManager {

    private val logger = LoggerFactory.getLogger(EventSourceManager::class.java)
    private val requestCache = mutableMapOf<UUID, Pair<ServerHttpRequest, ByteArray>>()
    private val eventSources = mutableMapOf<UUID, EventSource>()

    /**
 * 创建一个新的事件源，缓存POST请求及其请求体，并返回唯一标识。
 * 缓存的请求将在10秒后自动过期移除。
 * @param request 原始的POST HTTP请求
 * @return 事件源的唯一UUID标识，用于后续GET请求关联
 */
suspend fun createEventSource(request: ServerHttpRequest): UUID {
        val id = UUID.randomUUID()
        val body = DataBufferUtils.join(request.body).awaitSingleOrNull()
        val bytes = if (body != null) {
            val data = ByteArray(body.readableByteCount())
            body.read(data)
            DataBufferUtils.release(body)
            data
        } else byteArrayOf()
        requestCache[id] = request to bytes
        logger.info("【SSE】createEventSource: id={}, bodySize={}bytes, requestCache.size={}", id, bytes.size, requestCache.size)
        CoroutineScope(Dispatchers.Default).launch {
            delay(10.seconds)
            val removed = requestCache.remove(id)
            if (removed != null) {
                logger.warn("【SSE】createEventSource: request cache expired for id={}, GET request not received within 10s", id)
            }
        }
        return id
    }

    /**
 * 根据ID获取之前缓存的请求信息（请求对象和请求体）。
 * 获取后将从缓存中移除，保证一次性消费。
 * @param id 事件源UUID标识
 * @return 包含请求对象和请求体字节数组的Pair，若不存在则返回null
 */
fun getRequest(id: UUID): Pair<ServerHttpRequest, ByteArray>? {
        val result = requestCache.remove(id)
        if (result != null) {
            logger.info("【SSE】getRequest: found id={}, bodySize={}bytes, requestCache.size={}", id, result.second.size, requestCache.size)
        } else {
            logger.warn("【SSE】getRequest: NOT FOUND id={}, requestCache.size={}", id, requestCache.size)
        }
        return result
    }

    /**
 * 注册事件源到指定ID。
 * @param id 事件源UUID标识
 * @param source 事件源实例
 */
fun setEventSource(id: UUID, source: EventSource) {
        eventSources[id] = source
        logger.info("【SSE】setEventSource: id={}, eventSources.size={}", id, eventSources.size)
    }

    /**
 * 根据ID获取已注册的事件源。
 * @param id 事件源UUID标识
 * @return 事件源实例，若不存在则返回null
 */
fun getEventSource(id: UUID): EventSource? {
        val result = eventSources[id]
        if (result != null) {
            logger.debug("【SSE】getEventSource: found id={}, eventSources.size={}", id, eventSources.size)
        } else {
            logger.warn("【SSE】getEventSource: NOT FOUND id={}, eventSources.size={}", id, eventSources.size)
        }
        return result
    }

    /**
 * 移除指定ID的事件源。
 * @param id 要移除的事件源UUID标识
 */
fun removeEventSource(id: UUID) {
        val removed = eventSources.remove(id)
        if (removed != null) {
            logger.info("【SSE】removeEventSource: id={}, eventSources.size={}", id, eventSources.size)
        } else {
            logger.debug("【SSE】removeEventSource: id={} not found, eventSources.size={}", id, eventSources.size)
        }
    }
}