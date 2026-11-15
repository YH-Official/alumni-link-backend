package com.zxce.ai.util.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.isRegularFile
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.measureTime

/**
 * 验证纯 Kotlin MD5 实现与 Java 标准库的一致性，并统计性能。
 *
 * 测试策略：
 * 1. 从项目目录中随机选取 10 个不同大小的文件
 * 2. 对每个文件分别用本实现和 [MessageDigest] 计算 MD5
 * 3. 断言结果一致
 * 4. 输出耗时对比
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MD5Test {

    companion object {
        private const val SAMPLE_SIZE = 10
        private val projectRoot: Path = Path.of("").toAbsolutePath() // backend/
        private val repoRoot: Path = projectRoot.parent               // alumni-link/
    }

    /** 随机选取的测试文件信息 */
    private data class SampleFile(
        val path: Path,
        val sizeBytes: Long,
    ) {
        val sizeLabel: String get() = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${"%.1f".format(sizeBytes / 1024.0)} KB"
            else -> "${"%.2f".format(sizeBytes / (1024.0 * 1024))} MB"
        }
    }

    private lateinit var samples: List<SampleFile>

    @OptIn(ExperimentalPathApi::class)
    @BeforeAll
    fun pickSamples() {
        // 收集所有可用文件，排除构建/依赖目录
        val allFiles = Files.walk(repoRoot)
            .filter { it.isRegularFile() }
            .filter { path ->
                val rel = repoRoot.relativize(path).toString()
                !rel.contains(".git") &&
                !rel.contains("node_modules") &&
                !rel.contains("build") &&
                !rel.contains(".gradle") &&
                !rel.contains("voyager-src") &&
                !rel.contains(".idea") &&
                // 排除二进制/编译产物
                !rel.endsWith(".class") &&
                !rel.endsWith(".jar") &&
                !rel.endsWith(".png") &&
                !rel.endsWith(".jpeg") &&
                !rel.endsWith(".jpg")
            }
            .map { SampleFile(it, Files.size(it)) }
            .toList()

        println("找到 ${allFiles.size} 个可用文件")

        // 按大小分组，确保覆盖不同量级
        val tiny = allFiles.filter { it.sizeBytes < 1_000 }                   // < 1KB
        val small = allFiles.filter { it.sizeBytes in 1_000..10_000 }          // 1-10KB
        val medium = allFiles.filter { it.sizeBytes in 10_000..100_000 }       // 10-100KB
        val large = allFiles.filter { it.sizeBytes in 100_000..1_000_000 }     // 100KB-1MB
        val huge = allFiles.filter { it.sizeBytes > 1_000_000 }                // > 1MB

        // 从每组中抽样
        val selected = mutableSetOf<SampleFile>()
        val rng = Random(42)

        fun pickOne(pool: List<SampleFile>) {
            if (pool.isNotEmpty() && selected.size < SAMPLE_SIZE) {
                selected.add(pool[rng.nextInt(pool.size)])
            }
        }

        // 优先覆盖大文件（性能测试价值更大）
        repeat(3) { pickOne(huge) }
        repeat(2) { pickOne(large) }
        repeat(2) { pickOne(medium) }
        repeat(2) { pickOne(small) }
        repeat(1) { pickOne(tiny) }

        // 如果还没凑够，从剩余池补
        if (selected.size < SAMPLE_SIZE) {
            val remaining = allFiles.filter { it !in selected }.shuffled(rng)
            selected.addAll(remaining.take(SAMPLE_SIZE - selected.size))
        }

        samples = selected.toList().sortedBy { it.sizeBytes }
    }

    @Test
    fun `MD5 实现与 Java MessageDigest 结果一致`() {
        println("\n${"═".repeat(100)}")
        println("  MD5 正确性验证 — 共 ${samples.size} 个文件")
        println("${"═".repeat(100)}")
        println("  ${"%-6s".format("序号")} ${"%-50s".format("文件")} ${"%-10s".format("大小")} 结果")
        println("  ${"─".repeat(100)}")

        var passed = 0
        var failed = 0

        samples.forEachIndexed { idx, sample ->
            val bytes = Files.readAllBytes(sample.path)
            val relPath = repoRoot.relativize(sample.path).toString()

            // 本实现
            val ourHash = MD5.digest(bytes)

            // Java 标准库
            val jdkHash = MessageDigest.getInstance("MD5").digest(bytes)
                .joinToString("") { "%02x".format(it) }

            val ok = ourHash == jdkHash
            if (ok) passed++ else failed++

            val icon = if (ok) "✓" else "✗"
            val pathDisplay = if (relPath.length > 48) "...${relPath.takeLast(45)}" else relPath
            println("  %-6s %-50s %-10s  %s".format(
                "[${idx + 1}]",
                pathDisplay,
                sample.sizeLabel,
                "$icon  our=$ourHash  jdk=$jdkHash"
            ))
        }

        println("  ${"─".repeat(100)}")
        println("  结果: $passed 通过, $failed 失败")
        assertEquals(SAMPLE_SIZE, passed, "所有样本的 MD5 必须与 JDK 一致")
        println()
    }

    @Test
    fun `MD5 实现性能测试（流式 vs 全量 vs JDK）`() {
        println("\n${"═".repeat(100)}")
        println("  MD5 性能测试 — 耗时对比")
        println("${"═".repeat(100)}")
        println("  ${"%-6s".format("序号")} ${"%-50s".format("文件")} ${"%-10s".format("大小")}  ${"%-14s".format("本实现(全量)")} ${"%-14s".format("本实现(流式)")}  ${"%-14s".format("JDK MessageDigest")}")
        println("  ${"─".repeat(100)}")

        samples.forEachIndexed { idx, sample ->
            val bytes = Files.readAllBytes(sample.path)
            val relPath = repoRoot.relativize(sample.path).toString()
            val pathDisplay = if (relPath.length > 48) "...${relPath.takeLast(45)}" else relPath

            // 预热 JIT
            repeat(3) {
                MD5.digest(bytes)
                MessageDigest.getInstance("MD5").digest(bytes)
                sample.path.toFile().inputStream().use { MD5.digest(it) }
            }

            // 本实现 - 全量 ByteArray
            val ourBatchTime = measureTime {
                MD5.digest(bytes)
            }

            // 本实现 - 流式 InputStream
            val ourStreamTime = measureTime {
                sample.path.toFile().inputStream().use { MD5.digest(it) }
            }

            // JDK 标准
            val jdkTime = measureTime {
                MessageDigest.getInstance("MD5").digest(bytes)
            }

            println("  %-6s %-50s %-10s  %-14s  %-14s  %-14s".format(
                "[${idx + 1}]",
                pathDisplay,
                sample.sizeLabel,
                formatDuration(ourBatchTime),
                formatDuration(ourStreamTime),
                formatDuration(jdkTime),
            ))
        }

        println("  ${"─".repeat(100)}")
        println()
    }

    @Test
    fun `空数据 MD5 正确`() {
        val empty = ByteArray(0)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", MD5.digest(empty))
    }

    @Test
    fun `空 InputStream MD5 正确`() {
        val empty = ByteArray(0).inputStream()
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", MD5.digest(empty))
    }

    @Test
    fun `边界值 55 字节（填充后刚好 1 个块）`() {
        val data = ByteArray(55) { it.toByte() }
        val expected = MessageDigest.getInstance("MD5").digest(data)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, MD5.digest(data))
    }

    @Test
    fun `边界值 56 字节（填充后需要 2 个块）`() {
        val data = ByteArray(56) { it.toByte() }
        val expected = MessageDigest.getInstance("MD5").digest(data)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, MD5.digest(data))
    }

    @Test
    fun `边界值 63 字节`() {
        val data = ByteArray(63) { it.toByte() }
        val expected = MessageDigest.getInstance("MD5").digest(data)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, MD5.digest(data))
    }

    @Test
    fun `边界值 64 字节（恰好 1 个完整块）`() {
        val data = ByteArray(64) { it.toByte() }
        val expected = MessageDigest.getInstance("MD5").digest(data)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, MD5.digest(data))
    }

    @Test
    fun `边界值 128 字节`() {
        val data = ByteArray(128) { it.toByte() }
        val expected = MessageDigest.getInstance("MD5").digest(data)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, MD5.digest(data))
    }

    @Test
    fun `流式 InputStream 与全量 ByteArray 结果一致`() {
        val data = ByteArray(4096) { (it % 256).toByte() }
        val batchHash = MD5.digest(data)
        val streamHash = data.inputStream().use { MD5.digest(it) }
        assertEquals(batchHash, streamHash, "流式结果应与全量一致")
    }

    private fun formatDuration(d: Duration): String {
        val us = d.inWholeMicroseconds
        return when {
            us < 1_000 -> "${us} µs"
            us < 1_000_000 -> "${"%.2f".format(us / 1000.0)} ms"
            else -> "${"%.3f".format(us / 1_000_000.0)} s"
        }
    }
}