package com.zxce.ai.ui.view.index.components.cards

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.zxce.ai.ui.view.index.components.StatusCard
import java.lang.management.ManagementFactory
import javax.management.Attribute
import javax.management.ObjectName

@Preview
@Composable
fun CpuCard() {
    val mbsc by remember { mutableStateOf(ManagementFactory.getPlatformMBeanServer()) }
    val name by remember { mutableStateOf(ObjectName.getInstance("java.lang:type=OperatingSystem")) }
    StatusCard("CPU 使用率：") {
        val list = mbsc.getAttributes(name, arrayOf("ProcessCpuLoad"))
        if (list.isEmpty()) return@StatusCard null

        val att = list[0] as Attribute
        val value = att.value as Double
        return@StatusCard if (value < 0) null
        else ("%.1f".format(value * 100) + "%") to value
    }
}