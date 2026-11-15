package com.zxce.ai.manager

import com.fasterxml.jackson.databind.json.JsonMapper
import com.zxce.ai.annotation.NetworkSync
import com.zxce.ai.api.SystemApi
import com.zxce.ai.model.session.SessionEntity
import com.zxce.ai.model.session.query.QSessionEntity
import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.bundle.bundleScope
import com.zxce.ai.util.bundle.readBundle
import com.zxce.ai.util.ebean.*
import com.zxce.ai.util.isClient
import com.zxce.ai.util.network.Invoker
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.session.IWebExchangeContext
import com.zxce.ai.util.session.SessionMap
import com.zxce.ai.util.spring.ApplicationContextProvider
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.server.ServerWebExchange
import java.util.*

/**
 * 会话管理器，负责管理系统中所有用户的Session数据。
 * 支持Session的创建、获取、删除、持久化及网络同步。
 * 实现DisposableBean以在容器销毁时保存会话数据，实现CoroutineScope以支持异步操作。
 */
@Service
class SessionManager : DisposableBean, CoroutineScope {
    override val coroutineContext get() = Dispatchers.Default
    private val logger = LoggerFactory.getLogger(SessionManager::class.java)

    private val mSessions = mutableMapOf<UUID, SessionMapImpl>()

    @Autowired
    private lateinit var acp: ApplicationContextProvider

    @Autowired
    private lateinit var mapper: JsonMapper

    /**
 * 获取一个新的Session，自动生成随机SessionID。
 * @return 新建或已有的SessionMap
 */
fun get() = get(UUID.randomUUID().toString())
    /**
 * 根据SessionID字符串获取Session。
 * @param sessionId SessionID字符串
 * @return SessionMap
 */
operator fun get(sessionId: String) = get(sessionId.toUuid())

/**
 * 根据SessionID获取Session。若内存中不存在则从数据库加载，若仍不存在则创建新Session。
 * @param sessionId SessionID
 * @return SessionMap实例
 */
operator fun get(sessionId: UUID): SessionMap {
        var data = mSessions[sessionId] ?: findDatabase(sessionId)
        if (data.isNullOrEmpty()) {
            data = bundleScope {
                "sessionId" set sessionId.toString()
            }.toSession()
            mSessions[sessionId] = data
            acp.createModel<SessionEntity> {
                this.id = sessionId
                this.data = mapper.writeValueAsString(data)
            }.save()
        }
        data["session:lastAccess"] = Date().time
        return data
    }

    /**
 * 根据SessionID字符串移除Session。
 * @param sessionId 要移除的SessionID字符串
 */
fun remove(sessionId: String) = remove(sessionId.toUuid())

/**
 * 根据SessionID移除Session，同时删除数据库中的对应记录。
 * @param sessionId 要移除的SessionID
 */
fun remove(sessionId: UUID) {
        synchronized(mSessions) {
            mSessions.remove(sessionId)
        }
        acp.query<QSessionEntity> {
            id.eq(sessionId)

        }.delete()
    }

    /** 当前内存中缓存的Session数量。 */
val size get() = mSessions.size

    /**
 * 根据用户UUID获取其所有Session。
 * @param userId 用户UUID
 * @return 该用户的所有Session映射
 */
fun getUserSessions(userId: UUID) = getUserSessions(userId.toString())

/**
 * 根据用户ID字符串获取其所有Session。
 * @param userId 用户ID字符串
 * @return 该用户的所有Session映射（键为SessionID）
 */
fun getUserSessions(userId: String): Map<UUID, SessionMap> {
        synchronized(mSessions) {
            return mSessions.filterValues { it["userId"] == userId }
        }
    }

    /**
 * 根据上下文获取当前用户的Session。
 * @param context 请求上下文
 * @return 当前用户的Session，若未认证则返回null
 */
fun mySession(context: Context): SessionMap? {
        return if (context is IWebExchangeContext) mySession(context.exchange)
        else mySession(context.sessionId)
    }

    /**
 * 根据ServerWebExchange获取当前用户的Session。
 * @param exchange Web交换上下文
 * @return 当前用户的Session，若未认证则返回null
 */
fun mySession(exchange: ServerWebExchange): SessionMap? = mySession(exchange.request)

    /**
     * 获取本次请求的Session
     */
    fun mySession(req: ServerHttpRequest): SessionMap? {
        var auth = req.headers.getFirst(HttpHeaders.AUTHORIZATION) ?: return null
        if (auth.contains(' ')) auth = auth.split(' ', limit = 2)[1]

        val regex = let {
            val x = "[0-9a-f]"
            Regex("^$x{8}(-$x{4}){3}-$x{12}\$")
        }

        if (!regex.matches(auth)) return null
        return mySession(auth)
    }

    private fun mySession(sessionId: String) = mySession(sessionId.toUuid())
    private fun mySession(sessionId: UUID): SessionMap? {
        return if (sessionId in this) return this[sessionId]
        else null
    }

    /**
 * 判断指定SessionID字符串是否存在（内存或数据库）。
 * @param key SessionID字符串
 * @return 是否存在对应的Session
 */
operator fun contains(key: String) = contains(key.toUuid())

/**
 * 判断指定SessionID是否存在（内存或数据库）。
 * @param key SessionID
 * @return 是否存在对应的Session
 */
operator fun contains(key: UUID): Boolean {
        if (mSessions.containsKey(key)) return true
        return findDatabase(key) != null
    }

    /**
     * 从数据库查找一个session。
     * 若找到，返回它；若找不到或解析失败，返回null
     * @param sessionId SessionID
     */
    private fun findDatabase(sessionId: UUID): SessionMapImpl? {

        if (isClient) {
            return runBlocking {
                val bundle = withTimeout(5000) {
                    SystemApi.mySession(sessionId)
                }
                val session = bundle.toSession()
                synchronized(mSessions) {
                    mSessions[sessionId] = session
                }
                session
            }
        }

        // 非服务器模式，查询数据库

        val entity = acp.query<QSessionEntity> {
            id.eq(sessionId)
            deleted.ne(true)
        }.findOne() ?: return null
        return try {
            val data = SessionMapImpl(mapper.readBundle(entity.data))
            synchronized(mSessions) {
                mSessions[sessionId] = data
            }
            data
        } catch (e: Throwable) {
            logger.error("未能读取${sessionId}的Session信息", e)
            entity.delete()
            null
        }
    }

    /**
 * 保存指定Session到数据库。
 * @param sessionId SessionID字符串
 */
fun save(sessionId: String) = save(sessionId.toUuid())

/**
 * 保存指定Session到数据库。若Session不存在则忽略。
 * @param sessionId SessionID
 */
fun save(sessionId: UUID) {
        val session = mSessions[sessionId] ?: return
        val obj = acp.query<QSessionEntity> {
            id.eq(sessionId)
        }.findOne() ?: acp.createModel<SessionEntity> {
            id = sessionId
        }
        obj.applyDatabase(acp)
        obj.data = mapper.writeValueAsString(session)
        obj.save()
    }

    /**
 * 保存所有内存中的Session到数据库，并释放超时（7天未访问）的Session。
 * 同时通过网络同步通知其他节点释放已释放的Session。
 */
fun saveAll() {
        if (mSessions.isEmpty()) return
        var saveCounts = 0
        val needRelease = mutableSetOf<UUID>()
        val now = Date().time
        val entities = acp.query<QSessionEntity>().run {
            synchronized(mSessions) {
                id.isIn(mSessions.keys)
                findMap<UUID>()
            }
        }
        synchronized(mSessions) {
            entities += (mSessions.keys - entities.keys).associateBy({ it }) {
                SessionEntity().apply {
                    id = it
                }
            }
            mSessions.forEach { (key, entity) ->
                entities[key]!!.data = mapper.writeValueAsString(entity)
                val lastAccess = (entity["session:lastAccess"] as? Number)?.toLong() ?: Date().time
                if (lastAccess != entity["session:lastManagerAccess"]) {
                    entity["session:lastManagerAccess"] = lastAccess
                    saveCounts++
                }
                // 查询超时的Session信息，超时时间为7天
                if (now - lastAccess > 7 * 24 * 60 * 60_000L) needRelease.add(key)
            }
        }
        acp.database.saveAll(entities.values)
        // 仅在有session需要保存时打印日志
        if (saveCounts > 0) {
            logger.info("Auto saving $saveCounts of sessions.")
        }
        // 释放过时的Session信息，减少内存占用
        if (needRelease.isNotEmpty()) {
            synchronized(mSessions) {
                mSessions -= needRelease
            }
            postUserSessionReleaseSync(needRelease)
            logger.info("There are ${needRelease.size} sessions that have been released.")
        }
    }

    private var saveTimerJob: Job? = null

    @Scheduled(fixedDelay = 10000)
    private fun saveTimer() {
        // 客户端不需要触发Session保存
        if (isClient) return

        if (saveTimerJob != null) return
        saveTimerJob = launch(Dispatchers.IO) {
            try {
                saveAll()
            } catch (e: Throwable) {
                logger.error("自动保存Session信息时发生错误", e)
            } finally {
                saveTimerJob = null
            }
        }
    }

    /**
 * Spring容器销毁回调，等待正在进行的保存任务完成。
 */
override fun destroy() {
        try {
            // 正在保存数据时等待其完成后再保存一遍
            val job = saveTimerJob
            if (job != null) runBlocking {
                job.join()
            }
            // 不在此时操作。
            //saveAll()
        } catch (e: Throwable) {
            logger.error("保存Session信息时发生错误", e)
        }
    }

    //
    //  网络同步
    //

    @NetworkSync("user-session")
    private lateinit var postUserSessionSync: Invoker

    @NetworkSync("user-session")
    private fun onUserSessionSync(sessionId: UUID, key: String, value: Any?) {
        val session = mSessions[sessionId] ?: return
        session.netWorkSyncSet(key, value)
    }

    @NetworkSync("user-session-remove")
    private lateinit var postUserSessionRemoveSync: Invoker

    @NetworkSync("user-session-remove")
    private fun onUserSessionRemoveSync(sessionId: UUID, key: String, value: Any?) {
        val session = mSessions[sessionId] ?: return
        session.netWorkSyncRemove(key)
    }

    @NetworkSync("user-session-release")
    private lateinit var postUserSessionReleaseSync: Invoker

    @NetworkSync("user-session-release")
    private fun onUserSessionReleaseSync(sessionId: Set<UUID>) {
        mSessions -= sessionId
    }


    //
    // Session 实体
    //

    private fun Bundle.toSession(): SessionMapImpl {
        return SessionMapImpl(this)
    }


    /**
 * Session的内部实现类，基于Map实现，支持网络同步的set/remove操作。
 */
inner class SessionMapImpl private constructor(
        val map: MutableMap<String, Any?>, var2: Boolean
    ) : SessionMap, Map<String, Any?> by map {
        constructor() : this(mutableMapOf())
        constructor(map: Map<String, Any?>) : this(map.toMutableMap(), true)

        /** Session的唯一标识。 */
val id
            get() = map["sessionId"]?.toString()?.toUuid()
                ?: throw NoSuchElementException("No SessionId found!")

/** Session所属用户的UUID。 */
val userId
            get() = map["userId"]?.toString()?.toUuid()
                ?: throw NoSuchElementException("No UserId found!")

/**
 * 网络同步设置键值对。
 * @param key 键名
 * @param value 值
 */
fun netWorkSyncSet(key: String, value: Any?) {
            map[key] = value
        }

        /**
 * 网络同步移除键值对。
 * @param key 要移除的键名
 */
fun netWorkSyncRemove(key: String) {
            map.remove(key)
        }

        /**
 * 设置Session中的键值对，并通过网络同步通知其他节点。
 * @param key 键名
 * @param value 值
 */
override operator fun set(key: String, value: Any?) {
            map[key] = value
            postUserSessionSync(id, key, value)
        }

        /**
 * 移除Session中的键值对，并通过网络同步通知其他节点。
 * @param key 要移除的键名
 * @return 被移除的值
 */
override fun remove(key: String): Any? {
            val obj = map.remove(key)
            postUserSessionRemoveSync(id, key)
            return obj
        }
    }
}