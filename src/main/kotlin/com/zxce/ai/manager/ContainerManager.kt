package com.zxce.ai.manager

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.exception.NotFoundException
import com.github.dockerjava.api.model.DockerObjectAccessor
import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Image
import com.zxce.ai.config.SConfig
import com.zxce.ai.util.docker.Container
import com.zxce.ai.util.docker.IContainer
import com.zxce.ai.util.docker.IContainerProvide
import com.zxce.ai.util.docker.createDockerClient
import com.zxce.ai.util.property.asProperty
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.apply
import kotlin.collections.firstOrNull
import kotlin.collections.forEach
import kotlin.collections.getOrPut
import kotlin.collections.map
import kotlin.collections.set
import kotlin.let
import kotlin.text.startsWith
import kotlin.text.takeLast
import kotlin.time.Duration.Companion.seconds
import kotlin.to
import com.github.dockerjava.api.model.Container as DContainer

/**
 * 容器管理器，负责管理Docker容器的生命周期。
 * 维护Docker客户端实例池以实现负载均衡，支持容器的创建、查找、列出等操作。
 * 实现IContainerProvide接口，为上层服务提供容器访问能力。
 */
@Service
class ContainerManager : IContainerProvide {

    private val instances = ConcurrentHashMap<Int, InstanceWrapper<DockerClient>>()
    private val instanceCounter = AtomicInteger(0)


    override val containerRegister = mutableMapOf<String, MutableMap<String, IContainer>>()
    final override lateinit var imageMap: Map<String, Image>
        private set

    // 当 docker 服务路径更新时操作
    @Suppress("unused")
    private val containerRegisterListener = SConfig.aiServer.docker::host.asProperty().addListener {
        synchronized(containerRegister) {
            containerRegister.clear()
        }
    }

    /**
 * 初始化容器管理器，创建10个Docker客户端实例用于负载均衡。
 */
@PostConstruct
fun init() = CoroutineScope(Dispatchers.Default).launch {
        repeat(10) {
            createNewInstance()
        }
    }

    // 创建新实例
    private fun createNewInstance(): Int {
        if (instances.size >= 100) {
            throw kotlin.IllegalStateException("已达到最大实例数限制: 100")
        }

        val id = instanceCounter.incrementAndGet()
        instances[id] = InstanceWrapper(createDockerClient())
        return id
    }

    // 获取可用实例ID
    private suspend fun acquireAvailableInstanceId(): Int {
        // 首先尝试查找空闲实例
        instances.forEach { (id, wrapper) ->
            if (wrapper.isInUse.compareAndSet(0, 1)) {
                return id
            }
        }

        // 如果没有空闲实例但还可以创建新实例
        if (instances.size < 100) {
            val newId = createNewInstance()
            instances[newId]!!.isInUse.set(1)
            return newId
        }

        // 如果所有实例都在使用中且已达到上限，等待第一个可用的实例
        try {
            return withTimeout(10.seconds) {
                withContext(Dispatchers.Default) {
                    while (true) {
                        for ((id, wrapper) in instances) {
                            if (wrapper.isInUse.compareAndSet(0, 1)) {
                                return@withContext id
                            }
                        }
                        delay(100) // 短暂等待后重试
                    }
                    -1
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw kotlin.IllegalStateException("系统资源不足，等待超时，无法为你分配用于访问考试系统的资源了！", e)
        }
    }

    // 释放实例
    private fun releaseInstance(id: Int) {
        instances[id]?.isInUse?.set(0)
    }

    // 执行任务的作用域函数
    /**
 * 获取一个可用的Docker客户端实例并执行指定操作。
 * 使用互斥锁确保同一实例不会被并发访问，操作完成后自动释放实例。
 * @param block 要执行的操作，接收DockerClient参数
 * @return 操作的返回值
 */
suspend fun <R> withInstance(block: suspend (client: DockerClient) -> R): R {
        val instanceId = acquireAvailableInstanceId()
        val wrapper = instances[instanceId]!!

        try {
            // 使用互斥锁确保同一实例不会被并发访问
            return wrapper.mutex.withLock {
                coroutineScope {
                    withContext(Dispatchers.IO) {
                        block(wrapper.instance)
                    }
                }
            }
        } finally {
            CoroutineScope(Dispatchers.Default).launch {
                delay(50)
                releaseInstance(instanceId)
            }
        }
    }

    /**
 * 获取所有Docker容器列表（包括已停止的容器）。
 * @return 容器列表
 */
override suspend fun getContainers(): List<IContainer> {
        return withInstance { client ->
            client.listContainersCmd().apply {
                withShowAll(true)
            }.exec().map { Container(this, it) }
        }
    }


    /**
 * 根据容器ID获取容器。
 * @param id 容器ID
 * @return 容器实例，若不存在则返回null
 */
override suspend fun getContainerById(id: String): IContainer? {
        val list = withInstance { client ->
            client.listContainersCmd().apply {
                withShowAll(true)
                withIdFilter(listOf(id))
            }.exec()
        }
        if (list.isEmpty()) return null
        return Container(this, list[0].id)
    }

    /**
 * 根据容器名称获取容器。
 * @param name 容器名称
 * @return 容器实例，若不存在则返回null
 */
override suspend fun getContainerByName(name: String): IContainer? {
        val list = withInstance { client ->
            client.listContainersCmd().apply {
                withShowAll(true)
                withNameFilter(listOf(name))
            }.exec()
        }
        if (list.isEmpty()) return null
        return Container(this, list[0].id)
    }

    /**
     * 查找一个用户容器
     * @param user 用户
     * @param image 镜像
     */
    override suspend fun getContainer(user: String, image: String): IContainer? {
        // 判断是否存在缓存
        val userContainers = synchronized(containerRegister) {
            containerRegister.getOrPut(user) { mutableMapOf() }
        }

        // 已经存在了，直接返回
        userContainers[image]?.let { return it }

        // 查找容器
        val containers = withInstance { client ->
            client.listContainersCmd().apply {
                withShowAll(true)
                withLabelFilter(
                    mutableMapOf(
                        "com.zxce.ai.user" to user,
                        "com.zxce.ai.imageName" to image,
                        "com.zxce.system" to "exp-ai"
                    )
                )
            }.exec()
        }
        // 已经存在这个镜像了，直接返回
        containers.firstOrNull()?.let { return Container(this, it.id) }
        return null
    }

    /**
 * 将Docker容器元数据转换为[Container]实例。
 * @param meta Docker容器元数据
 * @return 容器实例
 */
override fun asContainer(meta: DContainer): Container {
        return Container(this, meta)
    }

    /**
     * 创建或返回已有的容器
     * @param user 用户
     * @param image 镜像
     * @param config 配置回调
     */
    override suspend fun createContainer(
        user: String,
        image: String,
        gpu: () -> Boolean,
        cmd: List<String>?,
        config: HostConfig.() -> Unit
    ): IContainer {
        val useGpu = gpu()

        getContainer(user, image)?.let {
            it.refresh()
            if (it.gpu == useGpu) return it
            // GPU设置不一致，移除它，稍后重新创建
            it.remove()
        }

        val hostConfig = HostConfig.newHostConfig().apply {
            // 是否允许学生使用GPU
            if (useGpu) {
                withRuntime("nvidia")
                DockerObjectAccessor.overrideRawValue(this, "gpus", "all")
            }
            withIpcMode("host")
            config(this)
        }

        val resp = withInstance {
            val cmd1 = withInstance { client ->
                client.createContainerCmd(image).apply {
                    // 随机容器名
                    withName("exp-ai-${UUID.randomUUID().toString().takeLast(8)}")
                    withHostConfig(hostConfig)
                    withLabels(
                        mutableMapOf(
                            "com.zxce.ai.user" to user,
                            "com.zxce.ai.imageName" to image,
                            "com.zxce.ai.useGpu" to useGpu.toString(),
                            "com.zxce.system" to "ai-communicate"
                        )
                    )
                    withAttachStderr(true)
                    withAttachStdout(true)
                    withAttachStdin(true)
                    withTty(true)

                    if (image.startsWith("ai_exp_conda310_img:")) {
                        withCmd(listOf("bash"))
                    } else {
                        cmd?.let { withCmd(it) }
                    }
                }
            }

            try {
                cmd1.exec()
            } catch (e: NotFoundException) {
                throw NoSuchElementException("学生镜像“${image}”不存在", e)
            }
        }

        return Container(this, resp.id)
    }

    class InstanceWrapper<T>(val instance: T) {
        val mutex = Mutex()
        val isInUse = AtomicInteger(0) // 0 = 空闲, 1 = 使用中
    }
}