package com.zxce.ai.util.spring

import org.springframework.context.ApplicationContext

interface ApplicationContextProvider {
    val applicationContext: ApplicationContext
}

fun ApplicationContextProvider(block: () -> ApplicationContext) = object : ApplicationContextProvider {
    override val applicationContext: ApplicationContext get()= block()
}