package com.zxce.ai.ui.util

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.zxce.ai.AlumniLink
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.NoSuchFileException
import javax.imageio.ImageIO
import kotlin.io.path.Path


/**
 * 图像加载工具
 * @author 洛洛希雅Lolosia
 * @since 2024-08-21 23:07
 */
object ImageLoader {

    /**
     * 从 drawable/ 路径下加载图像文件。
     * @param fileName 文件名，包括后缀名。
     * @throws NoSuchFileException 资源文件不存在
     */
    fun loadImageResource(fileName: String): Painter {
        val ist = AlumniLink::class.java.getResourceAsStream("drawable/$fileName")
            ?: throw NoSuchFileException("指定的资源文件“drawable/${fileName}”不存在！")
        val imageBitmap = ist.use {
            loadImageBitmap(ist)
        }
        return BitmapPainter(imageBitmap)
    }

    /**
     * 从文件路径加载图像。
     * @param path 文件路径，支持相对路径和绝对路径
     * @return Painter 图像对象，若文件不存在或加载失败则返回 null
     */
    fun loadImageFromFile(path: String): Painter? {
        return try {
            val file = Path(path).toFile()
            if (!file.exists()) return null
            file.inputStream().use { loadImageBitmap(it) }.let { BitmapPainter(it) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从 BootstrapIcon 创建图标。
     *
     * 查询图标：https://icons.getbootstrap.com/
     * @param icon 图标
     * @param size 图标大小
     * @param color ARGB颜色，默认为黑色不透明
     */
    fun loadImageBootstrap(
        icon: String,
        size: Int = 64,
        color: Int = 0xff000000.toInt()
    ): Painter {
        val iconBitmap = createIcon(icon, size, color).inputStream().use {
            loadImageBitmap(it)
        }
        return BitmapPainter(iconBitmap)
    }

    /** Bootstrap Icon 字体 */
    val bootstrapIconFont by lazy {
        AlumniLink::class.java.getResourceAsStream("bootstrap-icons.ttf")!!.use {
            Font.createFont(Font.TRUETYPE_FONT, it)
        }
    }

    /** Bootstrap Icon 字体映射表 */
    val bootstrapIconFontMap by lazy {
        AlumniLink::class.java.getResourceAsStream("bootstrap-icons.json")!!.use {
            ObjectMapper().readValue<Map<String, Int>>(it).toMap()
        }
    }

    /**
     * 创建一个 PNG 格式 Bootstrap 图标。
     *
     * 查询图标：https://icons.getbootstrap.com/
     * @param icon 图标
     * @param size 图标大小
     * @param color ARGB颜色，默认为黑色不透明
     */
    fun createIcon(icon: String, size: Int = 64, color: Int = 0xff000000.toInt()): ByteArray {

        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.font = bootstrapIconFont.deriveFont(size.toFloat())
        graphics.color = Color(color, true)

        val char = if (icon in bootstrapIconFontMap) bootstrapIconFontMap[icon]!!.toChar().toString()
        else bootstrapIconFontMap["question-circle-fill"]!!.toChar().toString()
        val fontMetrics = graphics.fontMetrics
        val textWidth = fontMetrics.stringWidth(char)
        val textHeight = fontMetrics.ascent
        val x = (size - textWidth) / 2
        val y = (size + textHeight) / 2
        graphics.drawString(char, x, y)
        graphics.dispose()
        val bos = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", bos)
        return bos.toByteArray()
    }
}