package com.zxce.ai.ui.layout.normal.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import com.zxce.ai.ui.store.systemUiState
import com.zxce.ai.ui.util.LocalWindowState

/** 控制台标题栏 */
@Composable
fun CustomTitleBar() {
    val windowState = LocalWindowState.current
    var isMaximized by systemUiState.current.mainWindow::isMaximized
    var showMainWindow by systemUiState.current.mainWindow::isShow

    val onMinimize = {
        windowState.isMinimized = true
    }

    val onMaximize = {
        isMaximized = !isMaximized
        windowState.placement = if (isMaximized) WindowPlacement.Maximized
        else WindowPlacement.Floating
    }

    val onClose = {
        showMainWindow = false
    }

    Row(
        modifier = Modifier.fillMaxWidth().fillMaxHeight().padding(8.dp)
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Icon(imageVector = Icons.Default.KeyboardArrowDown, // 最小化图标
            tint = MaterialTheme.colors.primary,
            contentDescription = "Minimize", modifier = Modifier.size(24.dp).clickable { onMinimize() })
        Spacer(modifier = Modifier.width(8.dp))

        Icon(imageVector = if (windowState.placement == WindowPlacement.Maximized) Icons.Default.AccountBox else Icons.Default.Menu,
            tint = MaterialTheme.colors.primary,
            contentDescription = "Maximize",
            modifier = Modifier.size(24.dp).clickable { onMaximize() })
        Spacer(modifier = Modifier.width(8.dp))

        Icon(imageVector = Icons.Default.Close, // 关闭图标
            tint = MaterialTheme.colors.primary,
            contentDescription = "Close", modifier = Modifier.size(24.dp).clickable { onClose() })
    }
}