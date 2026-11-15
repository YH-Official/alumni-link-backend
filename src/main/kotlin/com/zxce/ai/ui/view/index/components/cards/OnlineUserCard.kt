package com.zxce.ai.ui.view.index.components.cards

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.zxce.ai.AlumniLink
import com.zxce.ai.manager.EventBusSSEManager
import com.zxce.ai.ui.view.index.components.StatusCard
import org.springframework.beans.factory.getBean
import kotlin.math.max

@Preview
@Composable
fun OnlineUserCard() {

    val manager = remember {
        AlumniLink.applicationContext.getBean<EventBusSSEManager>()
    }

    StatusCard("在线人数：") {
        val clients = manager.size
        it.maxValue = max(clients.toDouble(), 1.0) * 1.4
        "$clients" to clients.toDouble()
    }
}
