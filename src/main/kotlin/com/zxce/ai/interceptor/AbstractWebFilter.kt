package com.zxce.ai.interceptor

import org.springframework.boot.web.reactive.filter.OrderedWebFilter

/**
 * 抽象Web过滤器基类，实现Spring的OrderedWebFilter接口。
 * 提供过滤器的排序优先级支持，所有自定义过滤器应继承此类。
 *
 * @param order 过滤器的排序值，数值越小优先级越高
 */
abstract class AbstractWebFilter(private val order: Int) : OrderedWebFilter {
    /**
     * 获取过滤器的排序值。
     *
     * @return 过滤器的排序优先级
     */
    override fun getOrder(): Int {
        return order
    }
}