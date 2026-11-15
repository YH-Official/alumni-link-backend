package com.zxce.ai.ui.view.init

import androidx.compose.foundation.layout.*
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zxce.ai.AlumniLink
import com.zxce.ai.service.ResourcesService
import com.zxce.ai.util.property.asProperty
import com.zxce.ai.util.property.asState
import org.springframework.beans.factory.getBean

@Composable
fun CheckResourcesView() {
    val ctx = remember { AlumniLink.applicationContext }
    val resSvc = remember { ctx.getBean<ResourcesService>() }
    val fileCount by remember { resSvc::fileCount.asProperty().asState() }
    val fileProcessed by remember { resSvc::fileProcessed.asProperty().asState() }
    val byteCount by remember { resSvc::byteCount.asProperty().asState() }
    val byteProcessed by remember { resSvc::byteProcessed.asProperty().asState() }

    val progress = if (byteCount != 0L) byteProcessed.toFloat() / byteCount.toFloat() else 0F

    Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.Center) {
        Text("正在检查资源完整性...")
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().padding(0.dp, 10.dp),
            progress = progress
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("已校检 $fileProcessed / $fileCount 个文件")
            Text("已完成 ${byteProcessed / 1048576L} / ${byteCount / 1048576L} MB")
        }
    }

}