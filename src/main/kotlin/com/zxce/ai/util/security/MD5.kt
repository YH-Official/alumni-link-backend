package com.zxce.ai.util.security

import java.io.InputStream

/**
 * 纯 Kotlin MD5 实现，无任何 Java 标准库之外的依赖。
 *
 * 与 KMP 端 `com.zxce.alk.util.security.MD5` 保持算法一致，
 * 额外提供 JVM 平台的 `InputStream` 重载。
 *
 * 对应 Web 前端 `oss.ts` 中使用的 SparkMD5.ArrayBuffer.hash()。
 */
object MD5 {

    // 初始链接变量
    private const val A0: Int = 0x67452301
    private const val B0: Int = -0x10325477  // 0xEFCDAB89
    private const val C0: Int = -0x67452302  // 0x98BADCFE
    private const val D0: Int = 0x10325476   // 0x10325476

    // 每轮使用的移位量
    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
    )

    // 正弦表常量 K[i] = floor(2^32 * abs(sin(i + 1)))
    private val K = IntArray(64) { i ->
        val s = kotlin.math.sin((i + 1).toDouble())
        (kotlin.math.abs(s) * 4294967296.0).toLong().toInt()
    }

    // ============================================================
    // 公开 API
    // ============================================================

    /**
     * 计算 ByteArray 的 MD5 十六进制字符串。
     */
    fun digest(data: ByteArray): String {
        val state = Md5State()
        val totalBits = data.size.toLong() * 8
        var offset = 0

        while (offset + 64 <= data.size) {
            processBlock(state, data, offset)
            offset += 64
        }

        finish(state, data, offset, data.size - offset, totalBits)

        return toHexLE(state.a) + toHexLE(state.b) + toHexLE(state.c) + toHexLE(state.d)
    }

    /**
     * 计算 InputStream 的 MD5 十六进制字符串。
     * 流式处理，内存占用恒定 O(64 bytes)，适合大文件。
     */
    fun digest(input: InputStream): String {
        val state = Md5State()
        var pending = ByteArray(0)
        var totalBits = 0L
        val buf = ByteArray(8192)

        while (true) {
            val read = input.read(buf)
            if (read == -1) break
            totalBits += read.toLong() * 8

            val chunk = if (read == buf.size) buf else buf.copyOf(read)
            val combined = if (pending.isEmpty()) chunk else pending + chunk
            val blockEnd = combined.size - (combined.size % 64)

            var offset = 0
            while (offset + 64 <= blockEnd) {
                processBlock(state, combined, offset)
                offset += 64
            }

            pending = combined.copyOfRange(blockEnd, combined.size)
        }

        finish(state, pending, 0, pending.size, totalBits)

        return toHexLE(state.a) + toHexLE(state.b) + toHexLE(state.c) + toHexLE(state.d)
    }

    // ============================================================
    // 内部 MD5 状态与块处理
    // ============================================================

    private class Md5State {
        var a: Int = A0
        var b: Int = B0
        var c: Int = C0
        var d: Int = D0
    }

    private fun processBlock(state: Md5State, block: ByteArray, offset: Int) {
        val words = IntArray(16)
        for (i in 0 until 16) {
            val bo = offset + i * 4
            words[i] = (block[bo].toInt() and 0xFF) or
                    ((block[bo + 1].toInt() and 0xFF) shl 8) or
                    ((block[bo + 2].toInt() and 0xFF) shl 16) or
                    ((block[bo + 3].toInt() and 0xFF) shl 24)
        }

        var aa = state.a
        var bb = state.b
        var cc = state.c
        var dd = state.d

        for (i in 0 until 64) {
            val (f, g) = when (i) {
                in 0..15 -> (bb and cc) or (bb.inv() and dd) to i
                in 16..31 -> (dd and bb) or (dd.inv() and cc) to ((5 * i + 1) % 16)
                in 32..47 -> bb xor cc xor dd to ((3 * i + 5) % 16)
                else -> cc xor (bb or dd.inv()) to ((7 * i) % 16)
            }
            val temp = dd
            dd = cc
            cc = bb
            bb = bb + ((aa + f + K[i] + words[g]) shl S[i] or ((aa + f + K[i] + words[g]) ushr (32 - S[i])))
            aa = temp
        }

        state.a += aa
        state.b += bb
        state.c += cc
        state.d += dd
    }

    private fun finish(state: Md5State, data: ByteArray, offset: Int, len: Int, totalBits: Long) {
        val blockCount = if (len + 1 + 8 <= 64) 1 else 2
        val padded = ByteArray(blockCount * 64)

        data.copyInto(padded, 0, offset, offset + len)
        padded[len] = 0x80.toByte()

        var bits = totalBits
        for (i in 0 until 8) {
            padded[padded.size - 8 + i] = (bits and 0xFF).toByte()
            bits = bits shr 8
        }

        processBlock(state, padded, 0)
        if (blockCount > 1) {
            processBlock(state, padded, 64)
        }
    }

    // ============================================================
    // 输出格式化
    // ============================================================

    private fun toHexLE(value: Int): String {
        val b0 = (value and 0xFF).toByte()
        val b1 = ((value ushr 8) and 0xFF).toByte()
        val b2 = ((value ushr 16) and 0xFF).toByte()
        val b3 = ((value ushr 24) and 0xFF).toByte()
        return byteToHex(b0) + byteToHex(b1) + byteToHex(b2) + byteToHex(b3)
    }

    private fun byteToHex(b: Byte): String {
        val hex = "0123456789abcdef"
        return "${hex[(b.toInt() ushr 4) and 0xF]}${hex[b.toInt() and 0xF]}"
    }
}