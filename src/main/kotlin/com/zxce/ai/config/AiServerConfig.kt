package com.zxce.ai.config

import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.bundle.invoke
import com.zxce.ai.util.config.ChainProp
import com.zxce.ai.util.config.ChangeableMap
import com.zxce.ai.util.config.NodeChainConfig
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpSseClientProperties
import org.yaml.snakeyaml.nodes.MappingNode
import kotlin.collections.mapValues

/**
 * AI服务器配置类，继承NodeChainConfig。
 * 管理AI相关服务的配置，包括Docker、SSH、HeyGem、Embedding、Milvus、Elastic等服务。
 * 同时配置幻灯片转换、数字人、TTS、SRS流媒体等外部服务地址。
 */
class AiServerConfig(parent: NodeChainConfig, node: MappingNode) : NodeChainConfig(parent, node) {

    val docker = DockerConfig(this, getMappingNodeOrCreate("docker"))
    val ssh = SSHConfig(getBundleOrCreate("ssh"))
    val heygem = HeyGemConfig(getBundleOrCreate("heygem"))
    val embedding = EmbeddingConfig(getBundleOrCreate("embedding"))
    val milvus = MilvusConfig(getBundleOrCreate("milvus"))
    val mcp = getBundleOrCreate("mcp").mapValues { (k, v) ->
        v as Bundle
        McpSseClientProperties.SseParameters(v("url"), v("sseEndpoint"))
    }

    @ChainProp("slide-transform-server")
    var slideTransformServer by notnull("http://172.17.0.1:10004")

    @ChainProp("meta-human-server")
    var metaHumanBaseServer by notnull("http://172.17.0.1:10001")

    @ChainProp("tts-generate-server")
    var ttsGenerateBaseServer by notnull("http://172.17.0.1:10003")

    @ChainProp("srs-server")
    var srsServer by notnull("rtmp://172.17.0.1:11935/live/livestream")

    val elastic = ElasticConfig(getBundleOrCreate("elastic"))

    @ChainProp("exam-server")
    val examServer by notnull("http://172.17.0.1:18001")

    /**
     * Docker服务配置。
     */
    class DockerConfig(parent: NodeChainConfig, node: MappingNode) : NodeChainConfig(parent, node) {
        var host by notnull("tcp://localhost:2375")
    }

    /**
     * SSH连接配置。
     */
    class SSHConfig(override val data: Bundle) : ChangeableMap() {
        val host by this("localhost")
        val port by this(22)
        val user by this("root")
        val password by this("123456")
    }

    /**
     * HeyGem服务配置，包含TTS和人脸服务地址。
     */
    class HeyGemConfig(override val data: Bundle) : ChangeableMap() {
        val ttsServer by this("http://172.17.0.1:18180")
        val faceServer by this("http://172.17.0.1:8383")
    }

    /**
     * Elasticsearch配置。
     */
    class ElasticConfig(override val data: Bundle) : ChangeableMap() {
        val url by this("https://localhost:9201")
        val user by this("elastic")
        val password by this("")
    }

    /**
     * 向量嵌入服务配置。
     */
    class EmbeddingConfig(override val data: Bundle) : ChangeableMap() {
        val baseUrl by this("http://172.17.0.1:7027/")
        val apiKey by this("123456")
        val model by this("jina-embeddings-v3")
    }

    /**
     * Milvus向量数据库配置。
     */
    class MilvusConfig(override val data: Bundle) : ChangeableMap() {
        val host by this("localhost")
        val port by this(19530)
    }
}