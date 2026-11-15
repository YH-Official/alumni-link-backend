package com.zxce.ai.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import com.zxce.ai.AlumniLink
import com.zxce.ai.config.ParentConfig
import com.zxce.ai.config.SConfig
import com.zxce.ai.service.NetworkSyncService
import com.zxce.ai.service.NetworkSyncService.Status.CONNECTED
import com.zxce.ai.service.NetworkSyncService.Status.DISCONNECTED
import com.zxce.ai.service.ResourcesService
import com.zxce.ai.ui.view.init.InitPage
import com.zxce.ai.util.property.asProperty
import com.zxce.ai.util.property.asState
import org.springframework.beans.factory.getBean
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Window.Type.UTILITY
import java.io.File
import java.net.URI
import javax.imageio.ImageIO
import kotlin.io.path.Path

/**
 * Initialization
 * @author 洛洛希雅Lolosia
 * @since 2024-10-27 15:49
 */

@Composable
fun InitializationWindow() {

    val init by AlumniLink.applicationInitialized
    val mode by remember { SConfig.host.serviceParent::mode.asProperty().asState() }
    var loaded by remember { mutableStateOf(false) }

    val status = if (!init) {
        remember { mutableStateOf(DISCONNECTED) }
    } else {
        val ctx = remember { AlumniLink.applicationContext }
        val service = remember { ctx.getBean<NetworkSyncService>() }
        remember { service::status.asProperty().asState() }
    }

    val checkRes by if (!init) {
        remember { mutableStateOf(false) }
    } else {
        val ctx = remember { AlumniLink.applicationContext }
        val service = remember { ctx.getBean<ResourcesService>() }
        remember { service::metadataChecking.asProperty().asState() }
    }

    val visible = if (checkRes) true
    else if (mode == ParentConfig.HostType.SERVER) !init
    else status.value != CONNECTED

    LaunchedEffect(status) {
        snapshotFlow { status.value }.collect {
            if (!loaded && SConfig.compose.openBrowserOnStart && it == CONNECTED) {
                loaded = true
                @Suppress("BlockingMethodInNonBlockingContext")
                Desktop.getDesktop().browse(URI("http://127.0.0.1:${SConfig.server.port}/expAi.2/"))
            }
        }
    }

    Window(
        visible = visible,
        create = {
            ComposeWindow().apply {
                isAlwaysOnTop = true
                isResizable = false
                isUndecorated = true
                isTransparent = true
                type = UTILITY
                size = Dimension(740, 240)
                title = "智慧先锋人工智能平台"
                setLocationRelativeTo(null)
                try {
                    val logoFile = Path("work/image/system/logo.jpg").toFile()
                    if (logoFile.exists()) iconImage = ImageIO.read(logoFile)
                } catch (_: Exception) {}
            }
        },
        dispose = { window ->
            window.dispose()
        }
    ) {

        MaterialTheme {
            Surface(shape = RoundedCornerShape(10.dp)) {
                WindowDraggableArea {
                    Box(
                        modifier = Modifier.padding(10.dp)
                            .fillMaxSize()
                            .background(Color.White),
                    ) {
                        InitPage()
                    }
                }
            }
        }
    }
}