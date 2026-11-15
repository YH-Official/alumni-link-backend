package com.zxce.ai.manager

import com.zxce.ai.model.system.SysCacheEntity
import com.zxce.ai.model.system.query.QSysCacheEntity
import com.zxce.ai.util.ebean.*
import com.zxce.ai.util.spring.ApplicationContextProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.sql.Timestamp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * 缓存管理器，基于数据库实现的通用缓存服务。
 * 通过路径(path)隔离不同的缓存命名空间，提供MutableMap接口以键值对方式操作缓存。
 */
@Service
class CacheManager {
    @Autowired
    private lateinit var acp: ApplicationContextProvider

    /**
 * 获取指定路径的缓存访问器。
 * @param path 缓存路径，用于隔离不同的缓存命名空间
 * @return 缓存访问器实例
 */
fun getCacheAccessor(path: String) = Accessor(path)

/**
 * 通过路径获取缓存访问器。
 * @param path 缓存路径
 * @return 缓存访问器实例
 */
operator fun get(path: String) = getCacheAccessor(path)

    /**
 * 缓存访问器，实现[MutableMap]接口，以路径为命名空间操作数据库缓存。
 * 所有操作均持久化到数据库，支持过期时间机制。
 */
inner class Accessor(val path: String) : MutableMap<String, String> {
        val acp get() = this@CacheManager.acp

        /**
 * 获取当前路径的缓存查询对象，默认过滤已过期记录。
 * @return Ebean查询对象
 */
fun query() = query { }

        /** 查询缓存，默认不查询过期的缓存记录。  */
        inline fun query(block: QSysCacheEntity.() -> Unit): QSysCacheEntity {
            val query = acp.query<QSysCacheEntity>()
            query.path.eq(path)
            query.expiresAt.gt(Timestamp(System.currentTimeMillis()))
            block(query)
            return query
        }

        /** 创建一个缓存，默认的过期时间是 1 天 */
        inline fun createModel(block: SysCacheEntity.() -> Unit): SysCacheEntity {
            val entity = acp.createModel<SysCacheEntity>()
            entity.path = path
            block(entity)
            return entity
        }

        /** 创建一个缓存并存入数据库，默认的过期时间是 1 天 */
        inline fun create(block: SysCacheEntity.() -> Unit): SysCacheEntity {
            val neo = createModel(block)
            val old = query().tag.eq(neo.tag).findOne()
            if (old != null) {
                old.value = neo.value
                old.expiresAt = neo.expiresAt
                old.update()
                return old
            }
            else {
                neo.insert()
                neo.refresh()
                return neo
            }
        }

        /**
         * 添加一个缓存，默认的过期时间是 1 天
         * @param tag 标签
         * @param value 值
         * @param expiresAt 过期时间
         */
        fun put(tag: String, value: String, expiresAt: Duration): String {
            val entity = create {
                this.tag = tag
                this.value = value
                this.expiresAt = Timestamp(System.currentTimeMillis() + expiresAt.inWholeMilliseconds)
            }
            return entity.value
        }

        /** 当前路径下的缓存条目数量。 */
        override val size: Int
            get() = query().findCount()

        /**
         * 判断缓存中是否包含指定键。
         * @param key 缓存键
         * @return 是否存在
         */
        override fun containsKey(key: String): Boolean {
            return query().tag.eq(key).exists()
        }

        /**
         * 判断缓存中是否包含指定值。
         * @param value 缓存值
         * @return 是否存在
         */
        override fun containsValue(value: String): Boolean {
            return query().value.eq(value).exists()
        }

        /**
         * 根据键获取缓存值。
         * @param key 缓存键
         * @return 缓存值，若不存在则返回null
         */
        override fun get(key: String): String? {
            return query {
                tag.eq(key)
                createdAt.desc()
            }.findOne()?.value
        }

        /**
         * 判断缓存是否为空（是否存在未过期的记录）。
         * @return 是否为空
         */
        override fun isEmpty(): Boolean {
            return query().exists()
        }

        /** 当前路径下所有缓存条目的只读集合。 */
        override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
            get() = query {
                createdAt.desc()
                tag.asMapKey()
            }.findMap<String>().mapValues { it.value.value }.toMutableMap().entries

        /** 当前路径下所有缓存键的只读集合。 */
        override val keys: MutableSet<String>
            get() = query {
                setDistinct(true)
                select(tag)
            }.findSingleAttributeSet()

        /** 当前路径下所有缓存值的只读集合。 */
        override val values: MutableCollection<String>
            get() = query { select(value) }.findSingleAttributeSet()

        /**
         * 设置缓存值，默认过期时间为1天。
         * @param key 缓存键
         * @param value 缓存值
         * @return 之前关联的值
         */
        override fun put(key: String, value: String): String {
            return put(key, value, 1.days)
        }

        /**
         * 批量设置缓存值，默认过期时间为1天。
         * @param from 要设置的键值对
         */
        override fun putAll(from: Map<out String, String>) {
            val entities = from.map {
                createModel {
                    tag = it.key
                    value = it.value
                    expiresAt = Timestamp(System.currentTimeMillis() + 1.days.inWholeMilliseconds)
                }
            }
            acp.database.transaction {
                acp.database.saveAll(entities, it)
            }
        }

        /**
         * 根据键移除缓存条目。
         * @param key 要移除的缓存键
         * @return 被移除的缓存值
         */
        override fun remove(key: String): String? {
            val out = get(key)
            query().tag.eq(key).delete()
            return out
        }

        /** 清除当前路径下所有缓存条目。 */
        override fun clear() {
            acp.database.transaction {
                query().delete(it)
            }
        }
    }
}