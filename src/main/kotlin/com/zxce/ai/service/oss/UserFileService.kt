package com.zxce.ai.service.oss

import com.fasterxml.jackson.databind.json.JsonMapper
import com.zxce.ai.model.oss.UserFileEntity
import com.zxce.ai.model.oss.query.QUserFileEntity
import com.zxce.ai.util.ErrorResponseException
import com.zxce.ai.util.ebean.createModel
import com.zxce.ai.util.ebean.database
import com.zxce.ai.util.ebean.query
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.session.IWebExchangeContext
import com.zxce.ai.util.spring.createStaticFileResponse
import com.zxce.ai.util.success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.*
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.*
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.outputStream
import kotlin.time.Duration.Companion.days

@Service
class UserFileService {

    companion object {
        /** 单个文件大小上限：50MB */
        private const val MAX_FILE_SIZE = 50L * 1024 * 1024

        /**
         * 清理客户端传入的文件名，去掉目录部分与控制字符，防止路径穿越。
         *
         * @param raw 客户端文件名
         * @return 可安全落盘的文件名
         */
        fun sanitizeFileName(raw: String): String {
            val name = raw.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[\\u0000-\\u001f]"), "")
                .trim()
            return name.ifEmpty { "unnamed" }
        }
    }

    @Autowired
    lateinit var mapper: JsonMapper

    /**
     * 根据MD5值查询当前用户的文件元信息
     *
     * @param ctx 上下文
     * @param md5 文件MD5值
     * @return 文件实体，若未找到则返回null
     */
    fun getFileMeta(ctx: Context, md5: String): UserFileEntity? {
        return ctx.query<QUserFileEntity> {
            this.md5.eq(md5)
            this.createdBy.eq(ctx.userIdOrNull)
        }.findOne()
    }

    /**
     * 根据MD5值获取当前用户的文件路径
     *
     * @param ctx 上下文
     * @param md5 文件MD5值
     * @return 文件路径，若文件不存在则返回null
     */
    fun getFile(ctx: Context, md5: String): Path? {
        val userId = ctx.userIdOrNull?.toString() ?: "guest"
        val dir = Path.of("work/files", userId, md5)
        if (!dir.exists()) return null
        return dir.toFile().listFiles()?.firstOrNull()?.toPath()
    }

    /**
     * 批量检查文件是否存在
     *
     * @param ctx 上下文
     * @param md5 文件MD5值列表
     * @return 布尔值列表，每个元素对应输入列表中同一位置的文件是否存在
     */
    suspend fun fileCheck(ctx: Context, md5: List<String>): List<Boolean> {
        val userId = ctx.userIdOrNull
        val has = ctx.query<QUserFileEntity> {
            createdBy.eq(userId)
            this.md5.isIn(md5)
        }.findList().map { it.md5 }.toSet()
        return md5.map { it in has }
    }

    /**
     * 根据MD5值获取文件响应，支持缓存和下载模式
     *
     * @param ctx 上下文，需实现IWebExchangeContext接口
     * @param md5 文件MD5值
     * @param download 是否以下载方式返回文件，默认为false
     * @return 文件响应实体，包含文件流和相关HTTP头
     */
    suspend fun get(ctx: Context, md5: String, download: Boolean = false): ResponseEntity<Flux<DataBuffer>> {
        ctx as IWebExchangeContext

        val lastModified = (ctx.session["req"] as? ServerHttpRequest)?.headers?.ifModifiedSince ?: -1
        // 检查文件是否已修改，未修改则返回304状态
        if (!download && lastModified > -1) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).lastModified(lastModified).build()
        }

        return ctx.createStaticFileResponse(maxAge = 7.days) {

            val path = getFile(ctx, md5) ?: throw ErrorResponseException(HttpStatus.NOT_FOUND, "文件不存在")
            val file = path.toFile()
            val fileFlux: Flux<DataBuffer> = DataBufferUtils.read(path, DefaultDataBufferFactory.sharedInstance, 8192)

            var mime = MediaTypeFactory.getMediaType(file.name).orElse(MediaType.APPLICATION_OCTET_STREAM)
            // JSON类型强制使用二进制流返回
            if (mime.isCompatibleWith(MediaType.APPLICATION_JSON)) mime = MediaType.APPLICATION_OCTET_STREAM

            headers {
                // 下载模式下设置Content-Disposition为附件
                if (download){
                    val urlFileName = URLEncoder.encode(file.name.toString(), Charsets.UTF_8);
                    it.contentDisposition = ContentDisposition.attachment().filename(urlFileName).build()
                }
                it.lastModified = file.lastModified()
                it.contentLength = file.length()
                it.contentType = mime
            }

            body(fileFlux)
        }
    }

    /**
     * 接收用户上传的文件（基于FilePart）
     *
     * @param ctx 上下文
     * @param file 本次上传的文件Flux流
     * @param reference 本次不需要上传的已存在文件MD5列表（仅添加引用）
     * @param service 文件所属服务名称
     * @return 成功响应
     */
    suspend fun uploadFromFileParts(ctx: Context, file: Flux<FilePart>, reference: List<String>, service: String): Any {
        val userId = ctx.userIdOrNull

        val iter = withContext(Dispatchers.IO) {
            file.toIterable()
        }

        upload<FilePart>(ctx, iter, service, { it.filename() }){ it, os ->
            it.content().asFlow().collect { dataBuffer ->
                val byteArray = dataBuffer.asInputStream().readBytes()
                DataBufferUtils.release(dataBuffer)
                withContext(Dispatchers.IO) {
                    os.write(byteArray)
                }
            }
        }

        val models = mutableListOf<UserFileEntity>()
        // 用户上传了已存在的文件，添加一个引用。
        if (reference.isNotEmpty()) {
            val files = ctx.query<QUserFileEntity> {
                this.md5.isIn(reference)
                this.createdBy.eq(userId)
                this.service.ne(service)
            }.findList().map {
                it.id = UUID.randomUUID()
                it.service = service
                it.createdAt = null
                it
            }
            models += files
        }
        ctx.database.insertAll(models)
        return success("ok")
    }

    /**
     * 通过输入流上传文件
     *
     * @param ctx 上下文
     * @param inputStream 文件输入流
     * @param fileName 文件名
     * @param service 文件所属服务名称
     * @return 上传成功后的文件MD5值
     */
    suspend fun uploadFromInputStream(ctx: Context, inputStream: InputStream, fileName: String, service: String) : String {
        val list = upload(ctx, listOf(inputStream), service, {fileName}){ it, os ->
            it.transferTo(os)
        }
        return list[0]
    }

    /**
     * 通用文件上传方法，支持泛型类型的文件上传
     *
     * @param ctx 上下文
     * @param file 待上传的文件集合
     * @param service 文件所属服务类型
     * @param getFileName 从文件对象获取文件名的函数
     * @param block 将文件内容写入输出流的回调函数
     * @return 上传成功的文件MD5值列表
     */
    private suspend fun <T> upload(
        ctx: Context,
        file: Iterable<T>,
        service: String,
        getFileName: (T) -> String,
        block: suspend (T, OutputStream) -> Unit
    ) : List<String> {
        val userId = ctx.userIdOrNull
        val dir = Path.of("work/files/${userId ?: "guest"}")
        withContext(Dispatchers.IO) {
            Files.createDirectories(dir)
        }
        val models = mutableListOf<UserFileEntity>()
        val md5 = file.map { filePart ->
            val fileName = sanitizeFileName(getFileName(filePart))
            val filePath = dir.resolve(fileName)
            val messageDigest = MessageDigest.getInstance("MD5")

            // 删除已存在的临时文件
            filePath.deleteIfExists()

            // 写入文件并计算MD5
            filePath.outputStream().use { os ->
                val os1 = object : OutputStream(){
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()))
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        messageDigest.update(b, off, len)
                        os.write(b, off, len)
                    }
                    override fun close() = os.close()
                    override fun flush() = os.flush()
                }
                block(filePart, os1)
            }
            // 完成MD5计算
            val md5Digest = messageDigest.digest()
            val md5Hex = md5Digest.joinToString("") { "%02x".format(it) }
            // 大小校验放到写盘之后，避免把超大文件留在磁盘上
            val fileSize = Files.size(filePath)
            if (fileSize > MAX_FILE_SIZE) {
                Files.deleteIfExists(filePath)
                throw IllegalArgumentException("文件大小不能超过 ${MAX_FILE_SIZE / 1024 / 1024}MB")
            }
            val fileTarget = dir.resolve(md5Hex)
            withContext(Dispatchers.IO) {
                Files.createDirectories(fileTarget)
                // 清空目标目录中的旧文件
                for (file1 in fileTarget.toFile().listFiles() ?: emptyArray()) {
                    file1.delete()
                }
                // 将临时文件移动到以MD5命名的目标目录
                Files.move(filePath, fileTarget.resolve(fileName))
            }

            // 创建文件实体记录
            models += ctx.createModel<UserFileEntity> {
                this.id = UUID.randomUUID()
                this.fileName = fileName
                this.md5 = md5Hex
                this.createdBy = userId
                this.service = service
            }

            return@map md5Hex
        }
        ctx.database.insertAll(models)

        return md5
    }
}