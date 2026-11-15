package com.zxce.ai.manager

import com.fasterxml.jackson.databind.json.JsonMapper
import com.zxce.ai.annotation.NetworkSync
import com.zxce.ai.service.NetworkSyncService
import com.zxce.ai.util.network.Invoker
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.getBean
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service
import java.lang.reflect.Method
import java.util.*

/**
 * 网络同步管理器，负责在分布式环境下同步跨节点的方法调用。
 * 通过注解[NetworkSync]标记同步方法，支持本地注册、远程调用和消息去重。
 */
@Service
class NetworkSyncManager {

    private val logger = LoggerFactory.getLogger(NetworkSyncManager::class.java)
    private val registry: MutableMap<String, LocalInvoker> = mutableMapOf()
    private val hashes = LinkedHashSet<String>()

    @Autowired
    lateinit var mapper: JsonMapper

    @Autowired
    lateinit var applicationContext: ApplicationContext

    val service by lazy { applicationContext.getBean<NetworkSyncService>() }

    /**
 * 注册一个带[NetworkSync]注解的方法为本地调用器。
 * @param obj 方法所属的对象实例
 * @param method 要注册的方法
 */
fun register(obj: Any, method: Method) {
        val ann = method.getAnnotation(NetworkSync::class.java)
        val clazzName = obj::class.java.name
        val namespace = "$clazzName/${ann.namespace}"
        registry[namespace] = LocalInvoker(obj, method, namespace)
    }

    /**
 * 获取一个远程调用器，用于将方法调用通过网络同步到其他节点。
 * @param obj 方法所属的对象实例
 * @param namespace 命名空间标识
 * @return 远程调用器，若本地未注册则返回null
 */
fun getRemoteInvoker(obj: Any, namespace: String): Invoker? {
        val clazzName = obj::class.java.name
        val namespace1 = "$clazzName/${namespace}"
        val localInvoker = registry[namespace1] ?: return null
        return RemoteInvoker(localInvoker)
    }

    /**
 * 处理接收到的网络同步消息。通过hash去重后，根据命名空间找到本地调用器并执行。
 * @param hash 消息唯一哈希值，用于去重
 * @param namespace 命名空间标识
 * @param message 序列化后的参数列表
 */
fun onNetworkSync(hash: String, namespace: String, vararg message: String) {
        synchronized(hashes) {
            if (hashes.contains(hash)) return

            hashes.add(hash)
            // 移除500个记录
            if (hashes.size > 1000) {
                var count = 0
                val iter = hashes.iterator()
                while (count < 500) {
                    iter.next()
                    iter.remove()
                    count++
                }
            }
        }

        val invoker = registry[namespace] ?: return
        val types = invoker.method.parameterTypes
        if (types.size != message.size) {
            throw IllegalArgumentException("Inconsistent number of parameters, need ${types.size} got ${message.size}")
        }
        val params = message.mapIndexed { i, it ->
            mapper.readValue(it, types[i])
        }
        invoker(*params.toTypedArray())
    }

    private fun postNetworkSync(invoker: LocalInvoker, vararg args: Any?) {
        val ws = service.sockets
        val namespace = invoker.namespace
        val hash = UUID.randomUUID().toString()
        synchronized(hashes) {
            hashes.add(hash)
        }
        val args1 = arrayOf(hash, namespace) + args.map {
            mapper.writeValueAsString(it)
        }
        val str = args1.joinToString("\n")

        logger.debug("SEND TO: ${str.replace("\n", ", ")}")

        ws.forEach {
            it.send(str)
        }
    }

    private class LocalInvoker(
        val obj: Any,
        val method: Method,
        val namespace: String,
    ) : Invoker {
        init {
            method.isAccessible = true
        }

        override operator fun invoke(vararg args: Any?) {
            method.invoke(obj, *args)
        }
    }

    private inner class RemoteInvoker(val localInvoker: LocalInvoker) : Invoker {
        override fun invoke(vararg args: Any?) {
            postNetworkSync(localInvoker, *args)
        }
    }
}