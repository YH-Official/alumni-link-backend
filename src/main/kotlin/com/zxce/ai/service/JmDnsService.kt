package com.zxce.ai.service

import com.zxce.ai.config.web.WebConfig
import com.zxce.ai.util.property.MutableProperty
import com.zxce.ai.util.property.TriggerProperty
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/**
 * JmDNS服务发现，基于mDNS协议自动发现局域网内的AI服务节点。
 * 监听_http._tcp.local.服务事件，维护可用服务列表。
 */
@Service
class JmDnsService : ServiceListener {

    @Autowired
    lateinit var jmDNS: WebConfig.JmDNSHolder

    // 已发现的服务地址集合
    private val mServices = mutableSetOf<String>()

    // 对外暴露的服务列表，通过TriggerProperty自动通知变更
    val services by TriggerProperty { mServices.toList() }

    // 当前平台是否支持mDNS
    final var isSupported by MutableProperty(false)
        private set

    /**
     * 初始化服务，注册mDNS监听器
     */
    @PostConstruct
    fun init() {
        if (jmDNS.jmDNS == null) return
        jmDNS().addServiceListener("_http._tcp.local.", this)
        isSupported = true
    }

    override fun serviceAdded(event: ServiceEvent) {}

    override fun serviceRemoved(event: ServiceEvent) {
        val ip = event.info.inet4Addresses.firstOrNull() ?: return
        val port = event.info.port
        mServices.remove("${ip.hostAddress}:$port")
        TriggerProperty.trigger(::services)
    }

    override fun serviceResolved(event: ServiceEvent) {
        if (event.name != "exp-ai-dns-service") return
        val ip = event.info.inet4Addresses.firstOrNull() ?: return
        val port = event.info.port
        mServices.add("${ip.hostAddress}:$port")
        TriggerProperty.trigger(::services)
    }
}