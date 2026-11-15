package com.zxce.ai.ui.components.tray

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * TrayMenu
 * @author 洛洛希雅Lolosia
 * @since 2024-10-27 18:15
 */

@Composable
fun TrayMenu(modifier: Modifier = Modifier, block: @Composable () -> Unit) {
    MaterialTheme {
        Surface(modifier, shape = RoundedCornerShape(5.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(horizontal = 5.dp)
            ) {
                block()
            }
        }
    }
}

@Preview
@Composable
fun TrayMenuPreview() {
    Box(modifier = Modifier.background(Color.Black).fillMaxSize()) {
        TrayMenu(
            modifier = Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            TrayMenuItem("测试")
            TrayMenuItem("测试1")
            TrayMenuItem("测试2")
            TrayMenuItem("测试3")
        }
    }
}