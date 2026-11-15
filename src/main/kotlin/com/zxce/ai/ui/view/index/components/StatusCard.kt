package com.zxce.ai.ui.view.index.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.seconds


@Composable
fun StatusCard(
    title: String,
    fontSize: TextUnit = 3.em,
    fontFamily: FontFamily? = null,
    meta: StatusMeta = StatusMeta(),
    statusUpdate: (StatusMeta) -> Pair<String, Double>?
) = StatusCard(title, fontSize, fontFamily, meta, statusUpdate) { }

@Composable
fun StatusCard(
    title: String,
    fontSize: TextUnit = 3.em,
    fontFamily: FontFamily? = null,
    meta: StatusMeta = StatusMeta(),
    statusUpdate: (StatusMeta) -> Pair<String, Double>?,
    block: @Composable () -> Unit
) {
    var text by remember { mutableStateOf("--") }
    val meta0 by remember { mutableStateOf(meta) }
    val usage = remember { mutableStateListOf(*Array(100) { 0.0 }) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1.seconds)
            val status = statusUpdate(meta0)
            if (status == null) {
                text = "--"
                continue
            }
            text = status.first
            if(usage.isNotEmpty()) usage.removeAt(0)
            usage.add(status.second)
        }
    }

    Surface(
        modifier = Modifier.heightIn(150.dp).fillMaxWidth().padding(15.dp),
        shape = RoundedCornerShape(8.dp),
        elevation = 10.dp
    ) {
        Canvas(modifier = Modifier.height(100.dp).width(200.dp)) {
            if (meta0.maxValue < 0) return@Canvas
            val (w, h) = size
            val brushWidth = w / 100
            if (meta0.autoMaxValue) {
                meta0.maxValue = usage.max() * 1.4
                if (meta0.maxValue == 0.0) meta.maxValue = 1.0
            }
            usage.map { (it / meta0.maxValue).toFloat() }.forEachIndexed { i, it ->
                if (it <= 0) return@forEachIndexed
                drawRect(
                    Color(0x660000ff),
                    Offset(i * brushWidth, h - h * it),
                    size = Size(brushWidth, h * it)
                )
            }
        }
        Text(title, Modifier.padding(10.dp))
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(0.dp, 10.dp, 0.dp, 0.dp)) {
            Text(
                text,
                style = TextStyle(
                    color = MaterialTheme.colors.primary,
                    fontSize = fontSize,
                    fontFamily = fontFamily,
                    shadow = Shadow(
                        color = Color.White,
                        blurRadius = 3f
                    )
                )
            )
        }
        block()
    }
}

data class StatusMeta(
    var maxValue: Double = 1.0,
    var autoMaxValue: Boolean = false,
)
