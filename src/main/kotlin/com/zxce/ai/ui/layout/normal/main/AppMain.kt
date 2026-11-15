package com.zxce.ai.ui.layout.normal.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zxce.ai.ui.util.LocalWindowScope

/**
 * 主显示区域
 * 顶栏为拖拽移动部分，下部为显示区域
 */
@Composable
fun AppMain(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        val windowScope = LocalWindowScope.current

        // 顶部可拖拽区域
        windowScope.WindowDraggableArea {
            Box(Modifier.fillMaxWidth().height(50.dp)) {
                Box(Modifier.fillMaxWidth().height(40.dp).background(Color(0xFFDDDDDD))) {}
                CustomTitleBar()
            }
        }

        // 右侧主显示区域
        Box(modifier = Modifier.fillMaxSize().padding(top = 10.dp, start = 20.dp, end = 20.dp)) {
            content()
        }
    }
}
