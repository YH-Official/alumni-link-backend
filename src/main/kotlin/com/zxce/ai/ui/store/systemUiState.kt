package com.zxce.ai.ui.store

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.painter.Painter
import com.zxce.ai.ui.util.ImageLoader
import com.zxce.ai.ui.view.index.IndexPage
import com.zxce.ai.ui.view.log.LogPage
import com.zxce.ai.ui.view.setting.SettingPage
import com.zxce.ai.ui.view.user.UserPage

/**
 * 系统UI状态
 * @author 洛洛希雅Lolosia
 * @since 2024-10-20 21:28
 */
class SystemUiState {
    val mainWindow = MainWindowState()
    val sideBar = SideBarState()
    val tray = TrayState()

    class MainWindowState {
        var isShow by mutableStateOf(false)
        var isMaximized by mutableStateOf(false)
        var blurValue by mutableStateOf(20f)
    }

    class SideBarState {
        var selectedMenu by mutableStateOf<SideBarMenuItem?>(null)
        val allMenus = mutableStateListOf<SideBarMenuItem>()
        val homePageIcon by lazy { ImageLoader.loadImageBootstrap("house-door", color = -1) }
        val userPageIcon by lazy { ImageLoader.loadImageBootstrap("people", color = -1) }
        val logPageIcon by lazy { ImageLoader.loadImageBootstrap("list-columns-reverse", color = -1) }
        val settingPageIcon by lazy { ImageLoader.loadImageBootstrap("gear", color = -1) }

        init {
            reset()
            selectedMenu = allMenus[0]
        }

        fun reset() {
            selectedMenu = null
            allMenus.clear()
            allMenus += mutableListOf(
                SideBarMenuItem("首页", homePageIcon) { IndexPage() },
                SideBarMenuItem("用户", userPageIcon) { UserPage() },
                SideBarMenuItem("日志", logPageIcon) { LogPage() },
                SideBarMenuItem("设置", settingPageIcon) { SettingPage() },
            )
        }
    }

    interface SideBarMenuItem {
        val title: String
        val painter: Painter?
        val isSplitter: Boolean
        val show: Boolean
        val component: (@Composable () -> Unit)?
    }

    class TrayState {
        var isShow = mutableStateOf(false)
        var toast = mutableStateOf<ToastData?>(null)
    }

    data class ToastData(val title: String, val message: String, val callback: (() -> Unit) = {})
}

/** 侧边栏菜单分割线 */
fun SideBarMenuSplitter(): SystemUiState.SideBarMenuItem = InternalSideBarMenuItem("", null, true)

/** 侧边栏菜单条目 */
fun SideBarMenuItem(
    title: String,
    painter: Painter,
    component: (@Composable () -> Unit)? = null
): SystemUiState.SideBarMenuItem {
    return InternalSideBarMenuItem(title, painter, component = component)
}

/** 内部菜单栏 */
private data class InternalSideBarMenuItem(
    override val title: String,
    override val painter: Painter?,
    override val isSplitter: Boolean = false,
    override val show: Boolean = true,
    override val component: (@Composable () -> Unit)? = null,
) : SystemUiState.SideBarMenuItem

val ComposeSystemUIState = SystemUiState()
val systemUiState = compositionLocalOf { ComposeSystemUIState }