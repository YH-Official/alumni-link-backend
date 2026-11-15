package com.zxce.ai.config.web

import com.zxce.ai.annotation.NetworkSync
import com.zxce.ai.manager.NetworkSyncManager
import com.zxce.ai.util.network.Invoker
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.context.event.ApplicationStartedEvent
import org.springframework.context.ApplicationContext
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.jvm.isAccessible

/**
 * 网络同步Bean后置处理器。
 * 扫描带有@NetworkSync注解的方法和字段，自动注册远程方法调用和Invoker注入。
 * 在应用启动完成后统一执行注册和注入操作。
 */
@Component
class NetworkSyncBeanPostProcessor : BeanPostProcessor {

    @Autowired
    lateinit var applicationContext: ApplicationContext

    val manager: NetworkSyncManager by lazy {
        applicationContext.getBean(NetworkSyncManager::class.java)
    }

    // 这两个属性将会在程序启动后被设置为null。
    var methodRecords: MutableList<() -> Unit>? = mutableListOf()
    var invokerRecords: MutableList<() -> Unit>? = mutableListOf()

    /**
     * 在Bean初始化前处理@NetworkSync注解。
     * 扫描bean中的方法和属性，收集需要注册的远程方法和Invoker注入操作，
     * 在应用启动后统一执行。
     *
     * @param bean 待处理的Bean实例
     * @param beanName Bean名称
     * @return 处理后的Bean
     */
    @Suppress("UNCHECKED_CAST")
    override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
        if (!bean::class.java.name.startsWith("com.zxce.ai")) return bean

        // 远程方法注册阶段
        var records = bean::class.java.declaredMethods
            .filter { !Modifier.isStatic(it.modifiers) && it.getAnnotation(NetworkSync::class.java) != null }
            .map { method ->
                { manager.register(bean, method) }
            }
        methodRecords?.let {
            it += records
        } ?: records.forEach { it() }

        // 字段的Invoker对象注入阶段
        records = bean::class.declaredMemberProperties
            .filterIsInstance<KMutableProperty1<Any, *>>()
            .filter {
                if (!it.hasAnnotation<NetworkSync>()) return@filter false
                return@filter (it.returnType.classifier as KClass<*>).isSubclassOf(Invoker::class)
            }
            .map { prop ->
                {
                    val ann = prop.findAnnotation<NetworkSync>()!!
                    manager.getRemoteInvoker(bean, ann.namespace)?.let {
                        prop.isAccessible = true
                        (prop as KMutableProperty1<Any, Invoker>).set(bean, it)
                    }
                    Unit
                }
            }

        invokerRecords?.let {
            it += records
        } ?: records.forEach { it() }

        return bean
    }


    /**
     * 应用启动事件监听方法。
     * 执行所有收集到的远程方法注册和Invoker注入操作，
     * 完成后清空记录列表。
     */
    @EventListener(ApplicationStartedEvent::class)
    fun doRegister() {
        methodRecords?.forEach { it() }
        invokerRecords?.forEach { it() }
        methodRecords = null
        invokerRecords = null
    }
}