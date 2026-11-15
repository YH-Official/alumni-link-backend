package com.zxce.ai.ui.view.setting.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zxce.ai.config.ParentConfig.HostType.CLIENT
import com.zxce.ai.config.ParentConfig.HostType.SERVER
import com.zxce.ai.config.SConfig
import com.zxce.ai.ui.view.setting.components.SettingItem
import com.zxce.ai.util.property.asProperty
import com.zxce.ai.util.property.asState

@Composable
fun BasicSetting(modifier: Modifier) {
    Column(modifier.widthIn(0.dp, 768.dp).fillMaxHeight()) {
        Text("本页面部分设置修改后需要重启", modifier=Modifier.padding(bottom=10.dp))

        var mode by remember { SConfig.host.serviceParent::mode.asProperty().asState() }
        SettingItem("客户端模式") {
            Switch(checked = mode == CLIENT, onCheckedChange = {
                mode = if (it) CLIENT else SERVER
            })
        }

        val openBrowser = remember { SConfig.compose::openBrowserOnStart }
        SettingItem("启动程序后打开浏览器", openBrowser)

        val port = remember { SConfig.server::port }
        SettingItem("服务端口", port)
    }
}