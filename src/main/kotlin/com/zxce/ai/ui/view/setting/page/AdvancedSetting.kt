package com.zxce.ai.ui.view.setting.page

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zxce.ai.config.SConfig
import com.zxce.ai.ui.view.setting.components.SettingFileItem
import com.zxce.ai.util.property.asProperty
import com.zxce.ai.util.property.asState

@Composable
fun AdvancedSetting(modifier: Modifier){
    Column(modifier.widthIn(0.dp, 768.dp).fillMaxHeight()) {
        Text("本页面部分设置修改后需要重启")

        var workDir by remember { SConfig.host::workDir.asProperty().asState() }
        SettingFileItem("程序工作目录", workDir) { workDir = it ?: workDir }
    }
}