package com.zxce.ai.util.sensitive

import com.zxce.ai.AlumniLink
import org.slf4j.LoggerFactory
import java.util.zip.GZIPInputStream

private val logger by lazy {
    LoggerFactory.getLogger(object {}::class.java.packageName)
}

val sensitiveWords: Map<Char, Array<String>> by lazy {

    try {
        val sensitive0 = mutableMapOf<Char, MutableList<String>>()
        val stream = AlumniLink::class.java.getResourceAsStream("sensitive_words_lines.bin")!!
        val gzip = GZIPInputStream(stream)
        val reader = gzip.reader().buffered()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) continue
            val list = sensitive0.getOrPut(line[0]) { mutableListOf() }
            list.add(line)
        }
        stream.close()
        sensitive0.mapValues { it.value.toTypedArray() }
    } catch (e: Throwable) {
        logger.error("读取敏感词信息失败", e)
        emptyMap()
    }
}

/**
 * 替换敏感词
 */
fun String.sensitive(): String {
    var out = this
    var index = 0;
    val length = this.length
    while (index < length) {
        val char = out[index]
        val maybe = sensitiveWords[char]

        if (!maybe.isNullOrEmpty()) {
            for (word in maybe) {
                if (out.startsWith(word, index)) {
                    out = out.replaceRange(index, index + word.length, "*".repeat(word.length))
                    index += word.length - 1
                    continue
                }
            }
        }

        index++
    }

    return out
}


/**
 * 判断本句话是否存在敏感词
 */
fun String.sensitiveCheck(): List<String> {
    val out = mutableListOf<String>()
    var index = 0;
    val length = this.length
    while (index < length) {
        val char = this[index]
        val maybe = sensitiveWords[char]

        if (!maybe.isNullOrEmpty()) {
            for (word in maybe) {
                if (this.startsWith(word, index)) {
                    out.add(word)
                    index += word.length - 1
                    continue
                }
            }
        }

        index++
    }

    return out
}