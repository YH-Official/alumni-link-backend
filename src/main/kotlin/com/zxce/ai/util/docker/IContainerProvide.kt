package com.zxce.ai.util.docker

import com.github.dockerjava.api.model.HostConfig
import com.github.dockerjava.api.model.Image

interface IContainerProvide {
    val imageMap: Map<String, Image>
    val containerRegister: MutableMap<String, MutableMap<String, IContainer>>

    suspend fun getContainers(): List<IContainer>
    suspend fun getContainerById(id: String): IContainer?
    suspend fun getContainerByName(name: String): IContainer?

    /**
     * 查找一个用户容器
     * @param user 用户
     * @param image 镜像
     */
    suspend fun getContainer(user: String, image: String): IContainer?
    fun asContainer(meta: DContainer): IContainer

    /**
     * 创建或返回已有的容器
     * @param user 用户
     * @param image 镜像
     * @param config 配置回调
     */
    suspend fun createContainer(
        user: String,
        image: String,
        gpu: () -> Boolean = { false },
        cmd: List<String>? = null,
        config: HostConfig.() -> Unit = {}
    ): IContainer
}