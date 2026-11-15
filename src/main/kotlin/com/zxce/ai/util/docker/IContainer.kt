package com.zxce.ai.util.docker

import com.github.dockerjava.api.model.Frame
import kotlinx.coroutines.flow.Flow
import java.util.*

interface IContainer {
    val provide: IContainerProvide
    val id: String
    var meta : DContainer
    val name: String
    val imageId: String
    val imageName: String
    val state: ContainerStatus
    val user: String?
    val port: Int?
    val system: String?
    val createdAt: Date
    val status: String
    val gpu: Boolean

    /** 启动容器 */
    suspend fun start()

    /** 停止容器 */
    suspend fun stop()

    /** 重启容器 */
    suspend fun restart()

    /** 移除容器 */
    suspend fun remove()

    /** 执行一条命令 */
    suspend fun exec(cmd: List<String>, pwd: String? = null, env: Map<String, String> = Collections.emptyMap()): Flow<Frame>

    /** 列出指定路径下所有文件 */
    suspend fun listFile(pwd: String): List<String>

    /** 列出指定路径下所有文件夹 */
    suspend fun listFolder(pwd: String): List<String>

    suspend fun listFile0(flow: Flow<Frame>): List<String>

    suspend fun fileExists(file: String): Boolean

    suspend fun createDir(dir: String)

    suspend fun removeFile(dir: String)

    suspend fun removeDir(dir: String)

    suspend fun copyFileToContainer(from: String, target: String)

    /** 更新容器状态 */
    suspend fun refresh(): DContainer
}