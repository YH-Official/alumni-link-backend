package com.zxce.ai.util.docker

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.DockerClientDelegate
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientImpl
import com.github.dockerjava.core.RemoteApiVersion
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import com.zxce.ai.config.SConfig
import com.zxce.ai.util.packageLogger
import com.zxce.ai.util.property.asProperty
import java.time.Duration

private val logger = packageLogger("com.zxce.ai.util.docker.PackageKt")

fun createDockerClient(): DockerClient {
    val host = SConfig.aiServer.docker::host.asProperty()
    val lock = Any();
    var instance : DockerClient? = null
    val listener = host.addListener {
        logger.info("当前 Docker 服务路径为 $it，正在启动 Docker 服务。")
        synchronized(lock) {
            val config = DefaultDockerClientConfig.createDefaultConfigBuilder().apply {
                // Docker服务器地址。
                withDockerHost(SConfig.aiServer.docker.host)
                withApiVersion(RemoteApiVersion.VERSION_1_43)
            }.build()

            val httpClient = ApacheDockerHttpClient.Builder().apply {
                dockerHost(config.dockerHost)
                // maxConnections(500)
                connectionTimeout(Duration.ofSeconds(90))
                responseTimeout(Duration.ofSeconds(120))
            }.build()

            instance?.close()
            val obj = DockerClientImpl.getInstance(config, httpClient)
            instance = obj
        }
    }

    listener(SConfig.aiServer.docker.host)

    return object : DockerClientDelegate() {
        @Suppress("unused")
        val listener = listener
        override fun getDockerClient(): DockerClient {
            return instance!!
        }
    }
}