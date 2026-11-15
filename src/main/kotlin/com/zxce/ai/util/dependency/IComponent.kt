package com.zxce.ai.util.dependency

import com.zxce.ai.AlumniLink
import com.zxce.ai.manager.ContainerManager
import kotlinx.coroutines.flow.Flow
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.getBean

interface IComponent {
    companion object{
        val manager by lazy {
            AlumniLink.applicationContext.getBean<ContainerManager>()
        }
    }
    val name: String
    val status: ComponentStatus
    val description: String get() = ""
    val log: Flow<String>
    val logger: Logger get() = LoggerFactory.getLogger(this.javaClass)
    suspend fun start()
    suspend fun stop()
}