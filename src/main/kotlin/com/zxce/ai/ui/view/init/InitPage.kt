package com.zxce.ai.ui.view.init

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zxce.ai.AlumniLink
import com.zxce.ai.config.ParentConfig
import com.zxce.ai.config.SConfig
import com.zxce.ai.service.JmDnsService
import com.zxce.ai.service.NetworkSyncService
import com.zxce.ai.service.NetworkSyncService.Status.CONNECTED
import com.zxce.ai.service.NetworkSyncService.Status.CONNECTING
import com.zxce.ai.service.ResourcesService
import com.zxce.ai.util.property.asProperty
import com.zxce.ai.util.property.asState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.beans.factory.getBean


@Composable
fun InitPage() {
    val init1 by AlumniLink.applicationInitialized

    if (!init1) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.Black)
                Text("请稍候")
            }
        }
        return
    }

    val ctx = remember { AlumniLink.applicationContext }
    val resSvc = remember { ctx.getBean<ResourcesService>() }
    val checkRes by remember { resSvc::metadataChecking.asProperty().asState() }

    if (checkRes){
        CheckResourcesView()
        return
    }

    val mode by remember { SConfig.host.serviceParent::mode.asProperty().asState() }
    if (mode == ParentConfig.HostType.SERVER) {
        Text("服务器正在运行……")
        return
    }

    val service = remember { ctx.getBean<NetworkSyncService>() }
    val status by remember { service::status.asProperty().asState() }
    val jmDnsService = remember { ctx.getBean<JmDnsService>() }

    if (status == CONNECTED) {
        Box(Modifier.fillMaxSize().padding(10.dp)) {
            Text("已连接到教师${SConfig.host.serviceParent.selected}", modifier = Modifier.align(Alignment.Center))
            Button(onClick = { service.disconnect() }, modifier = Modifier.align(Alignment.BottomEnd)) {
                Text("断开连接")
            }
        }
        return
    }

    if (status == CONNECTING) {
        Box(Modifier.fillMaxSize().padding(10.dp)) {
            Text("正在连接到教师${SConfig.host.serviceParent.selected}", modifier = Modifier.align(Alignment.Center))
            Button(onClick = { service.disconnect() }, modifier = Modifier.align(Alignment.BottomEnd)) {
                Text("取消")
            }
        }
        return
    }

    val jmdns by remember { jmDnsService::services.asProperty().asState() }
    val startTime = remember { System.currentTimeMillis() }
    var time by remember { mutableStateOf(0) }
    val cos = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        cos.launch {
            while (isActive) {
                time = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                delay(1000)
            }
        }
    }

    val isSupported by remember { jmDnsService::isSupported.asProperty().asState() }
    val selected by remember { SConfig.host.serviceParent::selected.asProperty().asState() }
    var manual by remember { mutableStateOf(false) }

    if (!manual && isSupported) Column {
        Row {
            Column(modifier = Modifier.weight(1f)) {
                Text("正在网络中搜索教师(${time}s)……")
                Text("在下侧选择教师以连接")
            }
            Button(onClick = { manual = true }) {
                Text("手动输入")
            }
        }


        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(5.dp)
        ) {
            if (selected != null) item {
                Button(onClick = { service.connect() }, modifier = Modifier.padding(5.dp)) {
                    Text("上次使用: $selected")
                }
            }

            items(jmdns) {
                Button(onClick = { service.connect(it) }, modifier = Modifier.padding(5.dp)) {
                    Text(it)
                }
            }
        }
    }
    else Column {
        if (!isSupported) {
            Text("您的设备不支持网络发现服务，请手动连接到一个教师。")
            Text("示例：192.168.xxx.xxx:7031")
        } else Row {
            Column(Modifier.weight(1f)) {
                Text("请手动连接到一个教师")
                Text("示例：192.168.xxx.xxx:7031")
            }

            Button(onClick = { manual = false }) {
                Text("自动搜索")
            }
        }
        var ip by remember { mutableStateOf("") }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                ip,
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent),
                onValueChange = { ip = it.trim() },
            )
            Button(onClick = { service.connect(ip) }, modifier = Modifier.padding(start = 10.dp)) {
                Text("连接")
            }
        }

        if (selected != null) {
            Button(onClick = { service.connect() }) {
                Text("上次使用: $selected")
            }
        }
    }
}