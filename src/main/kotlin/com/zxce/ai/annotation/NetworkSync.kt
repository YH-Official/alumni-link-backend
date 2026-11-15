package com.zxce.ai.annotation

/**
 * 表示当前属性中的内容需要在服务器全局同步
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
annotation class NetworkSync(val namespace: String = "")
