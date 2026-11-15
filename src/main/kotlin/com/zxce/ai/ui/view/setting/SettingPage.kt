package com.zxce.ai.ui.view.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zxce.ai.ui.view.setting.page.AdvancedSetting
import com.zxce.ai.ui.view.setting.page.BasicSetting

@Composable
fun SettingPage() {
    Row {
        var advancedSetting by remember { mutableStateOf(false) }
        Column(Modifier.width(240.dp)) {
            Button(onClick = { advancedSetting = false }, modifier = Modifier.fillMaxWidth()) {
                Text("基本设置")
            }
            Button(onClick = { advancedSetting = true }, modifier = Modifier.fillMaxWidth()) {
                Text("高级设置")
            }
        }

        Box(Modifier.fillMaxHeight().wrapContentWidth().padding(10.dp, 0.dp, 10.dp, 10.dp)) {
            Box(Modifier.background(Color.LightGray).width(1.dp).fillMaxHeight().align(Alignment.Center))
        }

        Box(Modifier.fillMaxSize()) {
            if (!advancedSetting) BasicSetting(Modifier.align(Alignment.Center))
            else AdvancedSetting(Modifier.align(Alignment.Center))
        }
    }
}