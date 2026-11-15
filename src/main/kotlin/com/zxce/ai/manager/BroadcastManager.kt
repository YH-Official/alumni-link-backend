package com.zxce.ai.manager

import com.zxce.ai.util.spring.ApplicationContextProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.getBean
import org.springframework.stereotype.Service

/**
 * 广播管理器，负责向所有SSE客户端或指定用户推送事件消息。
 * 通过EventBusSSEManager管理的SSE连接实现消息的广播与定向发送。
 */
@Service
class BroadcastManager {

    private val logger = LoggerFactory.getLogger(BroadcastManager::class.java)

    @Autowired
    lateinit var acp: ApplicationContextProvider

    val busManager by lazy {
        acp.applicationContext.getBean<EventBusSSEManager>()
    }

    /**
 * 向所有SSE客户端广播事件。
 * @param event 事件名称
 * @param data 事件数据，可以为任意可序列化对象
 */
fun broadcast(event: String, data: Any?) {
        if (event != "node:message" && event != "message") {
            logger.info("广播事件 {}, 数据: {}", event, data)
        } else {
            logger.debug("广播事件 {}, 数据: {}", event, data)
        }

        busManager.clients.values.map { it.client }.forEach {
            try {
                it.send(event, data)
            } catch (_: Throwable) {
            }
        }
    }

    /**
 * 向指定用户推送事件消息。
 * 根据用户ID查找其所有SSE连接，并逐一发送事件。
 * @param event 事件名称
 * @param data 事件数据，可以为任意可序列化对象
 * @param userId 目标用户ID
 */
fun broadcastToUser(event: String, data: Any?, userId: String) {
        if (event != "node:message" && event != "message") {
            logger.info("向用户 {} 发送事件 {}, 数据: {}", userId, event, data)
        } else {
            logger.debug("向用户 {} 发送事件 {}, 数据: {}", userId, event, data)
        }

        val sessions = busManager.getUserSessions(userId)
        if (sessions.isEmpty()) {
            logger.warn("【BroadcastManager】未找到用户 {} 的SSE连接! 当前在线连接数: {}, 事件: {}", userId, busManager.size, event)
            // 打印所有连接的userId，方便排查
            busManager.clients.entries.forEach { (sseId, meta) ->
                logger.debug("【BroadcastManager】在线连接: sseId={}, userId={}", sseId, meta.ctx.userIdOrNull)
            }
        } else {
            logger.info("【BroadcastManager】找到用户 {} 的 {} 个SSE连接, 开始推送事件: {}", userId, sessions.size, event)
        }

        sessions.values.map { it.client }.forEach { client ->
            try {
                client.send(event, data)
            } catch (e: Throwable) {
                logger.warn("【BroadcastManager】发送事件失败, sseId={}, event={}, error={}: {}", client.sseId, event, e::class.simpleName, e.message)
            }
        }
    }
}