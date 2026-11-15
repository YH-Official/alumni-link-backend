package com.zxce.ai.ui.view.index.components.cards

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zxce.ai.ui.view.index.components.StatusCard

@Preview
@Composable
fun JvmMemoryCard() {
    val max by remember { mutableStateOf(Runtime.getRuntime().maxMemory().toDouble()) }
    val text = "(上限:%.1fGB)".format(max / 0x40_000_000)
    StatusCard("进程内存使用率：", statusUpdate = {
        val runtime = Runtime.getRuntime()
        val total = runtime.totalMemory().toDouble()
        val using = total - runtime.freeMemory().toDouble()
        it.maxValue = total
        if (total <= 0x60_000_000) {
            val sTotal = (total / 0x100_000).toInt()
            val sUsing = (using / 0x100_000).toInt()
            return@StatusCard "$sUsing/${sTotal}M" to using
        } else {
            val sTotal = "%.1f".format(total / 0x40_000_000)
            val sUsing = "%.1f".format(using / 0x40_000_000)
            return@StatusCard "$sUsing/${sTotal}G" to using
        }
    }) {
        Text(text, textAlign = TextAlign.End, modifier = Modifier.padding(10.dp))
    }
}