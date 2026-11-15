package com.zxce.ai.util.dependency

import com.zxce.ai.util.docker.IContainer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList

interface IContainerProcessComponent : IComponent {

    companion object {
        @Suppress("FunctionName")
        fun IContainerProcessComponent.__equals(other: Any?): Boolean {
            if (other !is IContainerProcessComponent) return false
            return pid == other.pid
        }
    }

    val container: IContainer
    val pid: Int?

    override val status: ComponentStatus
        get() = pid?.let { ComponentStatus.Running } ?: ComponentStatus.NotExist

    override suspend fun start() = spawn()
    suspend fun spawn()

    override suspend fun stop() = kill()
    suspend fun kill(force: Boolean = false) {
        if (pid == null) return
        if (force) {
            container.exec(listOf("kill", "-9", pid.toString())).collect()
        } else {
            container.exec(listOf("kill", pid.toString())).collect()
        }
    }

    override val log: Flow<String>
        get() = channelFlow {
            val pid1 = pid ?: return@channelFlow
            val file = container.exec(listOf("readlink", "/proc/$pid1/fd/1")).toList().joinToString()
            if (!file.startsWith("/dev")) {
                container.exec(listOf("tail", "-f", file)).collect {
                    send(it.payload.decodeToString())
                }
            }
        }
}