package com.zxce.ai.controller

import com.zxce.ai.util.success
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 接收客户端回传的调试日志，追加写入 work/log/client-debug.log。
 * 用于在无法查看手机端控制台时排查 SSE 等问题。
 */
@RestController
@RequestMapping("/api/debug")
class DebugLogController {

    private val logger = LoggerFactory.getLogger(DebugLogController::class.java)

    private val logFile: Path by lazy {
        val dir = Path.of("work/log")
        Files.createDirectories(dir)
        dir.resolve("client-debug.log")
    }

    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    data class LogRequest(
        val messages: List<String>? = null,
        val message: String? = null,
        val device: String? = null
    )

    /**
     * 接收客户端回传的调试日志，追加写入日志文件。
     * 支持批量消息或单条消息，记录时间戳和设备信息。
     *
     * @param req 日志请求体，包含消息列表或单条消息以及设备标识
     * @return 操作成功的响应实体
     * @since 1.0.0
     */
    @PostMapping("/log")
    suspend fun receive(@RequestBody req: LogRequest): ResponseEntity<ByteArray> {
        val msgs = req.messages ?: (req.message?.let { listOf(it) } ?: emptyList())
        if (msgs.isEmpty()) return success()
        val ts = LocalDateTime.now().format(timeFormatter)
        val dev = req.device ?: "?"
        val sb = StringBuilder()
        for (m in msgs) {
            sb.append(ts).append(" [").append(dev).append("] ").append(m).append('\n')
        }
        try {
            Files.writeString(logFile, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: Exception) {
            logger.warn("写入客户端调试日志失败: ${e.message}")
        }
        return success()
    }
}
