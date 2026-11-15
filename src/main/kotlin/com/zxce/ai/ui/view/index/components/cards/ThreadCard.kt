package com.zxce.ai.ui.view.index.components.cards

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import com.zxce.ai.ui.view.index.components.StatusCard
import com.zxce.ai.ui.view.index.components.StatusMeta

@Preview
@Composable
fun ThreadCard() {
    StatusCard("线程数量：", meta = StatusMeta(autoMaxValue = true)) {
        val c = Thread.activeCount()
        return@StatusCard c.toString() to c.toDouble()
    }
}