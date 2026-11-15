package com.zxce.ai.ui.components.tray

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import com.zxce.ai.ui.store.systemUiState

/**
 * TrayMenuItem
 * @author 洛洛希雅Lolosia
 * @since 2024-10-27 18:37
 */

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TrayMenuItem(name: String, onClick: () -> Unit = {}) {
    var bg by remember { mutableStateOf(Color.White) }
    var isShow by systemUiState.current.tray.isShow
    Surface(
        modifier = Modifier.fillMaxWidth()
            .wrapContentHeight()
            .clickable(onClick = { isShow = false; onClick() })
            .padding(bottom = 3.dp)
            .onPointerEvent(PointerEventType.Enter) {
                bg = Color.LightGray
            }
            .onPointerEvent(PointerEventType.Exit) {
                bg = Color.White
            },
        shape = RoundedCornerShape(5.dp)
    ) {
        Box(
            modifier = Modifier.background(bg).wrapContentHeight()
        ) {
            Text(name, modifier = Modifier.padding(5.dp))
        }
    }
}

@Preview
@Composable
private fun TrayMenuItemPreview() {
    Box(modifier = Modifier.background(Color.Black).fillMaxSize()) {
        TrayMenuItem("测试", onClick = {})
    }
}