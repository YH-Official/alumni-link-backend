package com.zxce.ai.ui.view.index.components.cards

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zxce.ai.AlumniLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.springframework.core.env.get
import java.awt.Desktop
import java.net.NetworkInterface
import java.net.URI
import kotlin.time.Duration.Companion.seconds

@Preview
@Composable
fun NetWorkAddressCard() {
    val addresses = remember { mutableStateListOf<Pair<String, String>>() }
    LaunchedEffect(Unit) {
        while (isActive) {
            val app = AlumniLink.applicationContext
            if (app == null) {
                delay(1.seconds)
                continue
            }
            val port = app.environment["server.port"]

            var add: List<Pair<String, String>> = mutableListOf()
            val faces = NetworkInterface.getNetworkInterfaces()
            while (faces.hasMoreElements()) { // 遍历网络接口
                val face = faces.nextElement()
                if (face.isVirtual || !face.isUp) continue
                val address = face.inetAddresses
                val name = face.displayName
                while (address.hasMoreElements()) { // 遍历网络地址
                    val addr = address.nextElement()
                    (add as MutableList<Pair<String, String>>) += name to addr.hostAddress
                }
            }
            add = add
                .filter { !it.second.startsWith("fe80") }
                .map { (n, it) -> n to it.replace("%.+".toRegex(), "") }
            val ipv6 = add.filter { ":" in it.second }.map { (n, it) -> n to "[${it}]" }
            add = add.filter { ":" !in it.second } + ipv6
            add = add.map { (n, it) -> n to "$it:$port" }
            synchronized(addresses) {
                addresses.clear()
                addresses += add
            }
            delay(10.seconds)
        }
    }
    if (addresses.isEmpty()) return
    Surface(
        modifier = Modifier.height(200.dp).fillMaxWidth().padding(15.dp),
        shape = RoundedCornerShape(8.dp),
        elevation = 10.dp
    ) {
        Text("网络地址：", Modifier.padding(10.dp))
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(400.dp),
            contentPadding = PaddingValues(5.dp),
            modifier = Modifier.padding(10.dp, 30.dp, 10.dp, 10.dp)
        ) {
            val list = synchronized(addresses) {
                addresses.toList()
            }
            items(list) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().padding(10.dp, 5.dp),
                    contentPadding = PaddingValues(5.dp),
                    onClick = {
                        Desktop.getDesktop().browse(URI("http://${it.second}/"))
                    }
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(it.second)
                        Text(
                            it.first,
                            color = Color.Gray,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}