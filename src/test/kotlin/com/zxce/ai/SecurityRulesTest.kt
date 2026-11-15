package com.zxce.ai

import com.zxce.ai.interceptor.CorsWebFilter
import com.zxce.ai.service.oss.UserFileService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 安全相关纯函数的单元测试：CORS 来源白名单与上传文件名清理。
 */
class SecurityRulesTest {

    // ---------------- CORS ----------------

    @Test
    fun `白名单内的来源应被允许`() {
        assertEquals("http://127.0.0.1:7031", CorsWebFilter.resolveOrigin("http://127.0.0.1:7031"))
        assertEquals("http://localhost:7031", CorsWebFilter.resolveOrigin("http://localhost:7031"))
    }

    @Test
    fun `来源末尾斜杠会被规范化`() {
        assertEquals("http://127.0.0.1:7031", CorsWebFilter.resolveOrigin("http://127.0.0.1:7031/"))
    }

    @Test
    fun `白名单外的来源应被拒绝`() {
        assertNull(CorsWebFilter.resolveOrigin("https://evil.example.com"))
        assertNull(CorsWebFilter.resolveOrigin("http://127.0.0.1:7777"))
    }

    @Test
    fun `空来源返回 null`() {
        assertNull(CorsWebFilter.resolveOrigin(null))
        assertNull(CorsWebFilter.resolveOrigin(""))
        assertNull(CorsWebFilter.resolveOrigin("   "))
    }

    // ---------------- 文件名清理 ----------------

    @Test
    fun `路径穿越的文件名只保留最后一段`() {
        assertEquals("passwd", UserFileService.sanitizeFileName("../../etc/passwd"))
        assertEquals("a.png", UserFileService.sanitizeFileName("C:\\temp\\a.png"))
        assertEquals("b.jpg", UserFileService.sanitizeFileName("/var/tmp/b.jpg"))
    }

    @Test
    fun `控制字符会被移除`() {
        assertEquals("test.png", UserFileService.sanitizeFileName("te\u0000st\u001f.png"))
    }

    @Test
    fun `空文件名回退为 unnamed`() {
        assertEquals("unnamed", UserFileService.sanitizeFileName(""))
        assertEquals("unnamed", UserFileService.sanitizeFileName("   "))
    }

    @Test
    fun `正常文件名保持不变`() {
        assertEquals("avatar_01.webp", UserFileService.sanitizeFileName("avatar_01.webp"))
    }
}
