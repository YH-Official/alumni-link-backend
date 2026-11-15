package com.zxce.ai.ui.components.tray

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.Window
import com.zxce.ai.ui.store.systemUiState
import java.awt.Dimension
import java.awt.MouseInfo
import java.awt.Window.Type.UTILITY
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/**
 * TrayWindow
 * @author 洛洛希雅Lolosia
 * @since 2024-10-27 18:06
 */

@Composable
fun TrayWindow(block: @Composable () -> Unit) {
    var showTray by systemUiState.current.tray.isShow

    val location = MouseInfo.getPointerInfo().location

    Window(
        create = {
            ComposeWindow().apply {
                isAlwaysOnTop = true
                isResizable = false
                isUndecorated = true
                isTransparent = true
                type = UTILITY
                size = Dimension(200, 300)
                setLocation(location.x, location.y - 300)

                val listener = object : WindowFocusListener {
                    override fun windowGainedFocus(e: WindowEvent) {}
                    override fun windowLostFocus(e: WindowEvent) {
                        showTray = false
                    }
                }
                addWindowFocusListener(listener)
            }
        },
        dispose = { window ->
            window.dispose()
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            TrayMenu(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .wrapContentHeight(),
            ) {
                block()
            }
        }
    }
}