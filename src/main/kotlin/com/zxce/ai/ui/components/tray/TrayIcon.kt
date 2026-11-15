package com.zxce.ai.ui.components.tray

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import com.zxce.ai.AlumniLink
import com.zxce.ai.PROJECT_NAME
import com.zxce.ai.config.SConfig
import com.zxce.ai.service.NetworkSyncService
import com.zxce.ai.ui.store.systemUiState
import com.zxce.ai.ui.util.ImageLoader
import com.zxce.ai.util.property.asProperty
import com.zxce.ai.util.property.asState
import com.zxce.ai.VITE_DEFAULT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.springframework.beans.factory.getBean
import java.awt.Desktop
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseEvent.BUTTON1
import java.awt.event.MouseEvent.BUTTON3
import java.io.File
import java.net.URI
import javax.imageio.ImageIO
import kotlin.io.path.Path


/**
 * TrayIcon
 * @author 洛洛希雅Lolosia
 * @since 2024-10-27 16:18
 */

@Composable
fun SystemTrayIcon() {
    val uiState = systemUiState.current
    var showMainWindow by uiState.mainWindow::isShow

    val density = LocalDensity.current

    val trayState = remember { uiState.tray }
    var isShow by remember { trayState.isShow }
    var toast by remember { trayState.toast }
    val isDark = isSystemInDarkTheme()
    val awtIcon = remember {
        try {
            val logoFile = Path("work/image/system/logo.jpg").toFile()
            if (logoFile.exists()) ImageIO.read(logoFile)
            else {
                val icon = if (!isDark) ImageLoader.loadImageBootstrap("box-seam")
                else ImageLoader.loadImageBootstrap("box-seam", color = -1)
                icon.toAwtImage(density, LayoutDirection.Ltr, Size(16f, 16f))
            }
        } catch (_: Exception) {
            val icon = if (!isDark) ImageLoader.loadImageBootstrap("box-seam")
            else ImageLoader.loadImageBootstrap("box-seam", color = -1)
            icon.toAwtImage(density, LayoutDirection.Ltr, Size(16f, 16f))
        }
    }

    DisposableEffect(Unit) {
        val icon = TrayIcon(awtIcon, PROJECT_NAME)
        icon.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                when (e.button) {
                    BUTTON1 -> {
                        // showMainWindow = true
                        Desktop.getDesktop().browse(URI("http://127.0.0.1:${SConfig.server.port}/${VITE_DEFAULT}/"))
                    }

                    BUTTON3 -> isShow = true
                }
            }
        })
        var actionCallback: () -> Unit = {}
        icon.addActionListener {
            actionCallback()
        }
        SystemTray.getSystemTray().add(icon)
        launch {
            snapshotFlow { toast }.collect {
                if (it == null) return@collect
                toast = null
                actionCallback = it.callback
                icon.displayMessage(it.title, it.message, TrayIcon.MessageType.INFO)
            }
        }

        onDispose {
            SystemTray.getSystemTray().remove(icon)
        }
    }
    if (isShow) {
        TrayWindow {
            TrayMenuItem("主界面") {
                showMainWindow = true
            }
            if (AlumniLink.applicationInitialized.value) {
                val service = remember { AlumniLink.applicationContext.getBean<NetworkSyncService>() }
                val status by remember { service::status.asProperty().asState() }
                if (status == NetworkSyncService.Status.CONNECTED) {
                    TrayMenuItem("更换教师机") {
                        service.disconnect()
                    }
                }
            }
            val sideBar = systemUiState.current.sideBar
            TrayMenuItem("设置") {
                sideBar.selectedMenu = sideBar.allMenus.find { it.title == "设置" }
                showMainWindow = true
            }
            TrayMenuItem("退出") {
                launch {
                    AlumniLink.exitApplication()
                }
            }

        }
    }
}

private fun launch(block: suspend () -> Unit) {
    CoroutineScope(Dispatchers.Default).launch {
        block()
    }
}
