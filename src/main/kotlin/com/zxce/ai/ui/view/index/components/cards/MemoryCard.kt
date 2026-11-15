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
fun MemoryCard() {
    val mbsc by remember { mutableStateOf(ManagementFactory.getPlatformMBeanServer()) }
    val name by remember { mutableStateOf(ObjectName.getInstance("java.lang:type=OperatingSystem")) }
    StatusCard("内存使用率：") {
        val list = mbsc.getAttributes(name, arrayOf("TotalPhysicalMemorySize", "FreePhysicalMemorySize"))
        if (list.size != 2) return@StatusCard null

        var (all, usage) = list.map { ((it as Attribute).value as Number).toDouble() }.toMutableList()
        if (all + usage < 0) return@StatusCard null
        usage = all - usage

        val (sAll, sUsage) = listOf(all, usage).map {
            if (it >= 0) "%.1f".format(it / 0x40_000_000)
            else "--.-"
        }
        return@StatusCard "${sUsage}/${sAll}G" to (usage / all)
    }
}