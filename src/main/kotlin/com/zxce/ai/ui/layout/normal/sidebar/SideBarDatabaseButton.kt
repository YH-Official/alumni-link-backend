package com.zxce.ai.ui.layout.normal.sidebar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zxce.ai.config.SConfig
import com.zxce.ai.ui.util.ImageLoader
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.net.URI

/** 数据库管理入口的日志。 */
private val logger = LoggerFactory.getLogger("com.zxce.ai.ui.layout.normal.sidebar.DatabaseAdmin")

/**
 * 侧边栏左下角的数据库管理入口。
 *
 * 数据库管理页由后端提供（/admin/db），用系统默认浏览器打开，
 * 和托盘菜单、网络地址卡片打开 Web 页面的方式保持一致。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SideBarDatabaseButton(modifier: Modifier = Modifier) {
    val icon = remember { ImageLoader.loadImageBootstrap("database", color = -1) }
    TooltipArea(
        modifier = modifier,
        delayMillis = 400,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(14.dp, 0.dp)),
        tooltip = {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFF1F2329),
                elevation = 4.dp
            ) {
                Text(
                    text = "数据库管理",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
    ) {
        Box(
            modifier = Modifier.size(40.dp).clickable { openDatabaseAdminPage() },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = icon,
                contentDescription = "数据库管理",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

/** 在系统默认浏览器中打开数据库管理页。 */
private fun openDatabaseAdminPage() {
    val url = "http://127.0.0.1:${SConfig.server.port}/admin/db"
    try {
        Desktop.getDesktop().browse(URI(url))
    } catch (e: Exception) {
        logger.error("打开数据库管理页失败：$url", e)
    }
}