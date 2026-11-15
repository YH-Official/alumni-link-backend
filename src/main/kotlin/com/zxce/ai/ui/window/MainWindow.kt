package com.zxce.ai.ui.window

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.zxce.ai.AlumniLink
import com.zxce.ai.ui.layout.fullscreen.FullscreenView
import com.zxce.ai.ui.layout.normal.NormalLayout
import com.zxce.ai.ui.store.systemUiState
import com.zxce.ai.ui.util.ImageLoader
import com.zxce.ai.ui.util.LocalWindowScope
import com.zxce.ai.ui.util.LocalWindowState
import java.awt.Dimension

/**
 * MainWindow
 * @author 洛洛希雅Lolosia
 * @since 2024-10-20 20:00
 */
@Composable
fun MainWindow() {

    // 初始化窗口位置
    val windowState = rememberWindowState(
        position = WindowPosition.Aligned(Alignment.Center),
        size = DpSize(1366.dp, 768.dp)
    )
    var showMainWindow by systemUiState.current.mainWindow::isShow
    val appIcon = remember { ImageLoader.loadImageFromFile("work/image/system/logo.jpg") }

    Window(
        icon = appIcon,
        onCloseRequest = { showMainWindow = false },
        undecorated = true, // 禁用默认窗口装饰
        state = windowState,
        transparent = true, // 使窗口背景透明
        // position: center of screen
        title = "智慧先锋人工智能平台"
    ) {
        window.minimumSize = Dimension(1024, 768)

        val uiState = systemUiState.current

        // 主窗口内容
        MaterialTheme {
            Surface(
                shape = RoundedCornerShape(10.dp)
            ) {

                val initialized by AlumniLink.applicationInitialized

                // 没初始化完成的时候让屏幕转圈圈
                if (!initialized) {
                    FullscreenView {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color.Black)
                        }
                    }
                    return@Surface
                }

                // 传递 WindowScope 参数
                CompositionLocalProvider(
                    LocalWindowScope provides this@Window,
                    LocalWindowState provides windowState
                ) {
                    NormalLayout {
                        uiState.sideBar.selectedMenu?.component?.invoke() ?: Box {}
                    }
                }
            }
        }
    }
}
