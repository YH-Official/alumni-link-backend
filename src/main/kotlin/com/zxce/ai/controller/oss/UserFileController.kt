package com.zxce.ai.controller.oss

import com.zxce.ai.annotation.HostForward
import com.zxce.ai.service.oss.UserFileService
import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.bundle.bundleOf
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.session.IWebExchangeContext
import com.zxce.ai.util.session.SessionMap
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.codec.multipart.FormFieldPart
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

/**
 * 用户文件管理控制器
 *
 * 提供用户文件相关的RESTful API，包括文件上传、下载（公开/认证）、文件检查等功能。
 * 所有接口路径前缀为 /api/oss，需要用户登录认证（公开下载接口除外）。
 */
@HostForward
@RestController
@RequestMapping("/api/oss")
class UserFileController {

    @Autowired
    lateinit var service: UserFileService

    @Autowired
    lateinit var applicationContext: ApplicationContext

    data class FileCheckFn(val md5: List<String>)

    /**
     * 检查文件是否存在
     *
     * 根据MD5哈希值批量检查文件是否已存在于系统中。
     *
     * @param ctx 会话上下文，包含用户认证信息
     * @param params 包含 md5（MD5哈希值列表）的请求体
     * @return 与MD5列表对应的布尔值列表，表示每个文件是否存在
     * @since POST /api/oss/fileCheck
     */
    @PostMapping("/fileCheck")
    suspend fun fileCheck(ctx: Context, @RequestBody params: FileCheckFn): List<Boolean> {
        return service.fileCheck(ctx, params.md5)
    }

    /**
     * 下载文件（带认证）
     *
     * 根据用户ID和MD5哈希值下载文件，需要登录认证。
     *
     * @param ctx 会话上下文，包含用户认证信息
     * @param userId 用户ID（路径变量）
     * @param md5 文件MD5哈希值（路径变量）
     * @return 文件数据流响应
     * @since GET /api/oss/get/{userId}/{md5}
     */
    @GetMapping("/get/{userId}/{md5}")
    suspend fun get(
        ctx: Context,
        @PathVariable("userId") userId: String,
        @PathVariable("md5") md5: String
    ): ResponseEntity<Flux<DataBuffer>> {
        return service.get(ctx.mock(userId), md5, true)
    }


    /**
     * 下载文件（公开）
     *
     * 根据用户ID和MD5哈希值下载文件，以公开模式访问（不含认证信息）。
     *
     * @param ctx 会话上下文
     * @param userId 用户ID（路径变量）
     * @param md5 文件MD5哈希值（路径变量）
     * @return 文件数据流响应
     * @since GET /api/oss/show/{userId}/{md5}
     */
    @GetMapping("/show/{userId}/{md5}")
    suspend fun show(
        ctx: Context,
        @PathVariable("userId") userId: String,
        @PathVariable("md5") md5: String
    ): ResponseEntity<Flux<DataBuffer>> {
        return service.get(ctx.mock(userId), md5, false)
    }

    data class GetFn(val hash: String)

    /**
     * 通过POST方式下载文件
     *
     * 通过POST请求根据哈希值下载文件（带认证）。
     *
     * @param ctx 会话上下文，包含用户认证信息
     * @param params 包含 hash（文件哈希值）的请求体
     * @return 文件数据流响应
     * @since POST /api/oss/get
     */
    @PostMapping("/get")
    suspend fun getForPost(ctx: Context, @RequestBody params: GetFn): ResponseEntity<Flux<DataBuffer>> {
        return service.get(ctx, params.hash, true)
    }

    /** 使用一个Context对象来模拟用户信息 */
    private fun Context.mock(userId: String) = object : Context(), IWebExchangeContext {
        override val applicationContext get() = this@UserFileController.applicationContext
        override val exchange get() = (this@mock as IWebExchangeContext).exchange
        override val session: SessionMap by lazy {
            val x = "[0-9a-f]"
            val isUuid = Regex("^$x{8}(-$x{4}){4}$x{8}\$").matches(userId)

            val bundle = if (!isUuid) bundleOf()
            else bundleOf("id" to userId)

            return@lazy object : SessionMap, MutableMap<String, Any?> by bundle{
                override fun set(key: String, value: Any?) {

                }
            }
        }
    }


    /**
     * 上传文件
     *
     * 上传一个或多个文件到指定服务，支持附加引用信息。
     *
     * @param ctx 会话上下文，包含用户认证信息
     * @param file 上传的文件流（可选）
     * @param reference 引用信息表单字段（可选），用于关联业务数据
     * @param serviceName 目标服务名称，指定文件存储的服务
     * @return 上传结果，包含文件ID等信息
     * @since POST /api/oss/upload
     */
    @PostMapping("/upload")
    suspend fun upload(
        ctx: Context,
        @RequestPart("file", required = false) file: Flux<FilePart>?,
        @RequestPart("reference", required = false) reference: Flux<FormFieldPart>?,
        @RequestPart("service") serviceName: FormFieldPart
    ): Any {
        val files = file ?: Flux.empty()
        val refs = reference?.map { it.value() }?.asFlow()?.toList() ?: emptyList()

        return service.uploadFromFileParts(ctx, files, refs, serviceName.value())
    }
}