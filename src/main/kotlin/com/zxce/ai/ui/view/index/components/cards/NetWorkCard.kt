package com.zxce.ai.ui.view.index.components.cards

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import com.zxce.ai.interceptor.NetworkTrafficFilter
import com.zxce.ai.ui.view.index.components.StatusCard
import com.zxce.ai.ui.view.index.components.StatusMeta

@Preview
@Composable
fun NetWorkCard() {
    StatusCard(
        "网络流量：",
        fontSize = 2.em,
        fontFamily = FontFamily.Monospace,
        meta = StatusMeta(autoMaxValue = true)
    ) {
        fun getText(value: Double): String {
            val unit = listOf("b/s", "k/s", "M/s", "G/s")
            var size0 = value
            var unitIndex = 0
            while (size0 > 1024 && unitIndex <= unit.size) {
                size0 /= 1024
                unitIndex++
            }
            return "%.1f".format(size0) + unit[unitIndex]
        }

        val i = NetworkTrafficFilter.inboundBytes.toDouble()
        val o = NetworkTrafficFilter.outboundBytes.toDouble()
        val ic = NetworkTrafficFilter.inboundCount
        val oc = NetworkTrafficFilter.outboundCount
        val iS = "↑ %-4s %8s".format("${ic},", getText(i))
        val oS = "↓ %-4s %8s".format("${oc},", getText(o))
        return@StatusCard "${iS}\n${oS}" to (i + o)
    }
}