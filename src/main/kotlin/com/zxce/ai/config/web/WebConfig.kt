package com.zxce.ai.config.web

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.zxce.ai.AlumniLink.applicationContext
import com.zxce.ai.config.IOpenAiConfig
import com.zxce.ai.config.OpenAiType
import com.zxce.ai.config.ParentConfig
import com.zxce.ai.config.SConfig
import com.zxce.ai.util.property.PropertyCallback
import com.zxce.ai.util.property.asProperty
import com.zxce.ai.util.spring.ContextArgumentResolver
import com.zxce.ai.util.spring.THYMELEAF_APP
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder

import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer
import org.thymeleaf.spring6.web.webflux.SpringWebFluxWebApplication
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.jmdns.impl.JmDNSImpl

/**
 * Web配置类，实现WebFluxConfigurer接口。
 * 配置HTTP消息编解码、参数解析器、密码编码器、JSON序列化器、
 * Thymeleaf模板引擎、JmDNS服务发现以及AI聊天客户端等Bean。
 */
@Configuration
@EnableScheduling
class WebConfig : WebFluxConfigurer {

    val logger = LoggerFactory.getLogger(WebConfig::class.java)

    @Autowired
    lateinit var contextArgumentResolver: ContextArgumentResolver

    /**
     * 配置HTTP消息编解码器。
     * 文件上传走流式处理，这里只需覆盖常规 JSON 请求的内存缓冲，保持 32MB。
     *
     * @param configurer 编解码器配置器
     */
    override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
        configurer.defaultCodecs().maxInMemorySize(32 * 1024 * 1024)
    }

    /**
     * 注册自定义参数解析器，用于解析方法参数。
     *
     * @param configurer 参数解析器配置器
     */
    override fun configureArgumentResolvers(configurer: ArgumentResolverConfigurer) {
        configurer.addCustomResolver(contextArgumentResolver)
    }

    /**
     * 提供BCrypt密码编码器Bean。
     *
     * @return 密码编码器实例
     */
    @Bean
    fun getPasswordEncoder(): PasswordEncoder = BCryptPasswordEncoder(10)

    /**
     * 提供配置好的JSON映射器Bean。
     * 支持KotlinModule和JavaTimeModule，忽略未知属性。
     *
     * @return JsonMapper实例
     */
    @Bean
    fun getJsonMapper() = JsonMapper().apply {
        registerModule(KotlinModule.Builder().build())
        registerModule(JavaTimeModule())
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    /**
     * 提供Thymeleaf WebFlux应用Bean。
     *
     * @return SpringWebFluxWebApplication实例
     */
    @Bean
    fun getThymeleafApplication(): SpringWebFluxWebApplication {
        return THYMELEAF_APP
    }

    /**
     * 提供ApplicationContext提供者Bean，用于非Spring管理的类获取上下文。
     *
     * @return ApplicationContextProvider实例
     */
    @Bean
    fun getApplicationContextProvider(): com.zxce.ai.util.spring.ApplicationContextProvider {
        return com.zxce.ai.util.spring.ApplicationContextProvider { applicationContext }
    }

    private lateinit var jmDnsHook: PropertyCallback<ParentConfig.HostType>

    /**
     * 创建JmDNS服务发现Bean。
     * 根据主机模式自动注册/注销DNS服务。
     *
     * @return JmDNSHolder包装的JmDNS实例
     */
    @Bean
    fun jmDns(): JmDNSHolder {
        // JmDNS创建可能会失败
        try {
            val jmDns = JmDNS.create()
            val serviceName = "kotlin-spring-dns-service"
            val parent = SConfig.host.serviceParent

            jmDnsHook = parent::mode.asProperty().addListener {
                val service = (jmDns as JmDNSImpl).services[serviceName]

                if (it == ParentConfig.HostType.SERVER) {
                    if (service != null) return@addListener
                    val info = ServiceInfo.create(
                        "_http._tcp.local.",
                        serviceName,
                        SConfig.server.port,
                        "hello-world"
                    )
                    jmDns.registerService(info)
                } else {
                    if (service != null) {
                        jmDns.unregisterService(service)
                    }
                }
            }

            jmDnsHook(parent.mode)

            return JmDNSHolder(jmDns)
        } catch (e: Throwable) {
            logger.error("初始化JmDNS服务时发生异常", e)
            return JmDNSHolder(e = e)
        }
    }

    /**
     * JmDNS容器类，安全包装JmDNS实例，支持通过invoke获取。
     *
     * @param jmDNS JmDNS实例，可能为null
     * @param e 初始化异常，可能为null
     */
    data class JmDNSHolder(val jmDNS: JmDNS? = null, val e: Throwable? = null) {
        /**
         * 获取JmDNS实例，如初始化失败则抛出异常。
         *
         * @return JmDNS实例
         * @throws Throwable 初始化异常
         */
        operator fun invoke(): JmDNS {
            return jmDNS ?: throw e!!
        }
    }

    /**
     * 创建默认AI聊天客户端Bean。
     * 根据配置选择对应的AI服务提供商（OpenAI/Anthropic/DashScope），
     * 未配置时使用本地默认配置。
     *
     * @return ChatClient实例
     */
    @Bean
    fun chatClient(): ChatClient {
        if (SConfig.openApi.baseUrl.isBlank()) {
            logger.warn("AI 配置未设置，使用默认配置")
            return createDefaultChatClient()
        }
        return createChatClient(SConfig.openApi)
    }

    private fun createDefaultChatClient(): ChatClient {
        val api = OpenAiApi.builder()
            .baseUrl("http://localhost:8080/v1")
            .apiKey("dummy-key")
            .build()
        val options = OpenAiChatOptions.builder()
            .model("gpt-4o-mini")
            .temperature(0.7)
            .build()
        val model = OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(options)
            .build()
        return ChatClient.builder(model)
            .defaultSystem("A user has raised a question. Please answer him.")
            .build()
    }

    /**
     * 多AI客户端容器类，实现Map接口，按键名存储不同的ChatClient实例。
     *
     * @param clients 存储ChatClient的可变Map
     */
    class ChatClients(private val clients: MutableMap<String, ChatClient>) : Map<String, ChatClient> by clients

    /**
     * 创建多个AI聊天客户端Bean。
     * 根据配置中的clients映射创建多个不同的AI客户端实例。
     *
     * @return ChatClients容器实例
     */
    @Bean
    fun chatClients(): ChatClients {
        val map = mutableMapOf<String, ChatClient>()

        val configClients = SConfig.openApi.clients

        configClients.keys.forEach { key ->
            if (key == "default") {
                try {
                    map["default"] = chatClient()
                } catch (e: Throwable) {
                    logger.error("创建默认ChatClient时发生异常", e)
                }
                return@forEach
            }

            val config = configClients[key]
            try {
                if (config.baseUrl.isNotBlank()) {
                    map[key] = createChatClient(config)
                }
            } catch (e: Throwable) {
                logger.error("创建ChatClient[$key]时发生异常", e)
            }
        }

        return ChatClients(map)
    }

    private fun createChatClient(config: IOpenAiConfig): ChatClient {
        val model = when (config.type) {
            OpenAiType.OPENAI -> {
                val api = OpenAiApi.builder()
                    .baseUrl(config.baseUrl)
                    .completionsPath(config.completionsPath)
                    .apiKey(config.apiKey)
                    .build()

                val options = OpenAiChatOptions.builder()
                    .model(config.model)
                    .temperature(0.7)
                    .build()

                OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(options)
                    .build()

            }

            OpenAiType.ANTHROPIC -> {
                val api = AnthropicApi.builder()
                    .baseUrl(config.baseUrl)
                    .completionsPath(config.completionsPath)
                    .apiKey(config.apiKey)
                    .build()

                val options = AnthropicChatOptions.builder()
                    .model(config.model)
                    .temperature(0.7)
                    .maxTokens(4096)
                    .build()

                AnthropicChatModel.builder()
                    .anthropicApi(api)
                    .defaultOptions(options)
                    .build()
            }

            OpenAiType.DASH_SCOPE -> {
                val api = DashScopeApi.builder()
                    .baseUrl(config.baseUrl)
                    .completionsPath(config.completionsPath)
                    .apiKey(config.apiKey)
                    .build()

                val options = DashScopeChatOptions.builder()
                    .model(config.model)
                    .temperature(0.7)
                    .enableThinking(!config.noThink)
                    .multiModel(true)
                    .build()

                DashScopeChatModel.builder()
                    .dashScopeApi(api)
                    .defaultOptions(options)
                    .build()
            }

            else -> throw UnsupportedOperationException("OpenAiType ${config.type} is not supported")
        }

        val builder = ChatClient.builder(model)
        return builder.defaultSystem("A user has raised a question. Please answer him.")
            .build()
    }
}