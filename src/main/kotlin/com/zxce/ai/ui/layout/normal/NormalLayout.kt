package com.zxce.ai.ui.layout.normal

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zxce.ai.ui.layout.normal.main.AppMain
import com.zxce.ai.ui.layout.normal.sidebar.SideBar

/**
 * 标准布局
 * @author 洛洛希雅Lolosia
 * @since 2024-10-20 20:50
 */

@Composable
fun NormalLayout(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {

        Row(
            modifier = Modifier.fillMaxSize()
        ) {
            // 侧边栏
            Box(
                modifier = Modifier.width(50.dp).fillMaxHeight().padding(0.dp)
            ) {
                SideBar()
            }

            // 主内容
            Box(
                modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) {
                AppMain(content)
            }
        }
    }
}