package com.zxce.ai.util.dependency

import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import com.zxce.ai.util.docker.ContainerStatus
import com.zxce.ai.util.docker.IContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface IContainerComponent : IComponent {
    companion object {
        private val manager get() = IComponent.manager

        @Suppress("FunctionName")
        fun IContainerComponent.__equals(other: Any?): Boolean {
            if (other !is IContainerComponent) return false
            return cid == other.cid
        }
    }

    val cid: String

    val container: IContainer
        get() = runBlocking {
            manager.getContainerById(cid)
                ?: throw NoSuchElementException("找不到指定的容器")
        }

    val exists: Boolean
        get() = runBlocking { manager.getContainerById(cid) != null }

    override val status: ComponentStatus
        get() {
            if (!exists) return ComponentStatus.NotExist
            when (container.state) {
                ContainerStatus.Running -> return ComponentStatus.Running
                ContainerStatus.Restarting -> return ComponentStatus.Starting
                else -> Unit
            }
            return ComponentStatus.Exited
        }

    override val name: String
        get() = container.name

    val containerName: String

    override suspend fun stop() {
        if (exists) {
            container.stop()
            delay(50)
        }
    }

    override suspend fun start() {
        if (exists) {
            container.start()
            delay(50)
        }
    }

    override val log: Flow<String>
        get() = channelFlow {
            manager.withInstance { client ->
                suspendCancellableCoroutine { cont ->
                    val cmd = client.logContainerCmd(container.id)
                    cmd.exec(object : ResultCallback.Adapter<Frame>() {

                        override fun onError(throwable: Throwable) {
                            cont.resumeWithException(throwable)
                        }

                        override fun onComplete() {
                            cont.resume(Unit)
                        }

                        override fun onNext(obj: Frame) {
                            runBlocking {
                                send(obj.payload.decodeToString())
                            }
                        }
                    })

                    cont.invokeOnCancellation {
                        cmd.close()
                    }
                }
            }
        }

    fun getContainerOrNull() = try {
        container
    } catch (e: Throwable) {
        null
    }
}