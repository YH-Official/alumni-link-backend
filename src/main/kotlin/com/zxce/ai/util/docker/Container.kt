package com.zxce.ai.util.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.async.ResultCallback
import com.github.dockerjava.api.model.Frame
import com.github.dockerjava.api.model.StreamType
import com.zxce.ai.manager.ContainerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.path.*

class Container(
    override val provide: ContainerManager,
    override val id: String
) : IContainer {

    constructor(provide: ContainerManager, meta: DContainer) : this(provide, meta.id) {
        mMeta = meta
    }

    private val imageMap get() = provide.imageMap
    private val containerRegister get() = provide.containerRegister

    private var mMeta: DContainer? = null
    override var meta: DContainer
        get() = mMeta ?: runBlocking { refresh() }
        set(value) {
            if (value.id != id) throw IllegalArgumentException("ContainerId is not equaled!")
            mMeta = value
        }

    override val name
        get() = meta.names.firstOrNull()
            ?: id.substring(0 until 12)
    override val imageId
        get() = meta.imageId
            ?: throw NoSuchElementException("No image id found!")
    override val imageName
        get() = meta.labels["com.zxce.ai.imageName"] ?: getImageName0()
    override val state get() = ContainerStatus[meta.state]
    override val user get() = meta.labels["com.zxce.ai.user"]
    override val port get() = meta.ports.firstOrNull()?.publicPort
    override val system get() = meta.labels["com.zxce.system"]
    override val createdAt get() = Date(meta.created * 1000)
    override val status get() = meta.status!!
    override val gpu: Boolean
        get() {
            if (meta.labels["com.zxce.ai.useGpu"] == "false") return false
            return meta.labels["com.nvidia.volumes.needed"] != null
        }

    private suspend fun <R> withInstance(block: suspend (client: DockerClient) -> R): R {
        return provide.withInstance(block)
    }

    private fun getImageName0(): String {
        var id = imageId
        var name: String = imageMap[id]?.repoTags?.firstOrNull()
            ?: ("sha256:" + id.substring(0 until 12))
        do {
            name = imageMap[id]?.repoTags?.firstOrNull() ?: name
            id = imageMap[id]?.parentId ?: break
        } while (name.startsWith("sha256:"))

        return name
    }

    /**
     * 启动容器
     */
    override suspend fun start() {
        refresh()
        if (state == ContainerStatus.Running) return

        withInstance { client ->
            client.startContainerCmd(id).exec()
        }
    }

    /**
     * 停止容器
     */
    override suspend fun stop() {
        refresh()
        if (state in listOf(ContainerStatus.Exited, ContainerStatus.Created)) {
            return
        }
        withInstance { client ->
            client.stopContainerCmd(id).exec()
        }
    }


    /**
     * 重启容器
     */
    override suspend fun restart() {
        refresh()
        if (state in listOf(ContainerStatus.Exited, ContainerStatus.Created)) {
            withInstance { client ->
                client.startContainerCmd(id).exec()
            }
            return
        }
        withInstance { client ->
            client.restartContainerCmd(id).exec()
        }
    }

    /**
     * 移除容器
     */
    override suspend fun remove() {
        refresh()
        withInstance {  client ->
            client.removeContainerCmd(id).withForce(true).exec()
        }
        containerRegister[user]?.remove(imageName)
    }

    /**
     * 在Docker中执行一条命令
     */
    override suspend fun exec(cmd: List<String>, pwd: String?, env: Map<String, String>): Flow<Frame> {
        start()

        val obj = withInstance { client ->
            client.execCreateCmd(id).apply {
                withAttachStdout(true)
                withAttachStderr(true)
                withCmd(*cmd.toTypedArray())
                withWorkingDir(pwd)
                withEnv(env.map { (k, v) -> "$k=$v" })
            }.exec()
        }

        return channelFlow {
            withInstance { client ->
                suspendCancellableCoroutine {
                    var resumed = false
                    val cmd1 = client.execStartCmd(obj.id)
                    cmd1.exec(object : ResultCallback.Adapter<Frame>() {
                        override fun onNext(frame: Frame) {
                            runBlocking {
                                send(Frame(frame.streamType, frame.payload.copyOf()))
                            }
                        }

                        override fun onError(throwable: Throwable) {
                            super.onError(throwable)
                            CoroutineScope(Dispatchers.Default).launch {
                                it.resumeWithException(throwable)
                            }
                            resumed = true
                        }

                        override fun onComplete() {
                            super.onComplete()
                            if (resumed) return
                            CoroutineScope(Dispatchers.Default).launch {
                                it.resume(Unit)
                            }
                        }
                    })
                    it.invokeOnCancellation {
                        cmd1.close()
                    }
                }
            }
        }
    }

    /** 列出指定路径下所有文件 */
    override suspend fun listFile(pwd: String): List<String> {
        return listFile0(exec(listOf("find", ".", "-type", "f"), pwd))
    }

    /** 列出指定路径下所有文件夹 */
    override suspend fun listFolder(pwd: String): List<String> {
        return listFile0(exec(listOf("find", ".", "-type", "d"), pwd)).drop(1)
    }

    override suspend fun listFile0(flow: Flow<Frame>): List<String> {
        return flow.toList()
            .filter { it.streamType == StreamType.STDOUT }
            .joinToString { it.payload.decodeToString() }
            .split('\n')
            .map { it.removePrefix("./") }
            .filter { it.isNotEmpty() }
    }

    override suspend fun fileExists(file: String): Boolean {
        val str = exec(listOf("sh", "-c", "if [ -e \"${file}\" ]; then echo true; else echo false; fi"))
            .toList().joinToString("").trim()
        return when (str) {
            "STDOUT: true" -> true
            "STDOUT: false" -> false
            else -> throw RuntimeException(str)
        }
    }

    override suspend fun createDir(dir: String) {
        exec(listOf("mkdir", "-p", dir)).collect()
    }

    override suspend fun removeFile(dir: String) = removeDir(dir)
    override suspend fun removeDir(dir: String) {
        exec(listOf("rm", "-rf", dir)).collect()
    }

    override suspend fun copyFileToContainer(from: String, target: String) {
        var fromPath = Path(from)
        var tmpPath = false
        if (!fromPath.isDirectory()) {
            val tmp = Path("work/container/${UUID.randomUUID()}")
            tmp.createDirectories()
            fromPath.copyTo(tmp.resolve(fromPath.fileName))
            tmpPath = true
            fromPath = tmp
        }
        createDir(target)
        withInstance { client ->
            client.copyArchiveToContainerCmd(id).apply {
                withHostResource(fromPath.absolutePathString())
                withRemotePath(target)
                withDirChildrenOnly(true)
            }.exec()
        }

        @OptIn(ExperimentalPathApi::class)
        if (tmpPath) fromPath.deleteRecursively()
    }

    /**
     * 更新容器状态
     */
    override suspend fun refresh(): DContainer {
        withInstance { client ->
            mMeta = client.listContainersCmd().apply {
                withShowAll(true)
                withIdFilter(listOf(id))
            }.exec().first()
        }

        return mMeta!!
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Container) return false
        return id == other.id
    }
}