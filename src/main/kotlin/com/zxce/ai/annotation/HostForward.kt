package com.zxce.ai.annotation

/**
 * 表示当前方法需要被重定向到中心节点
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class HostForward(val value: Boolean = true)