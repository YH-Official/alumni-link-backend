package com.zxce.ai.ui.layout.normal.sidebar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zxce.ai.ui.store.systemUiState

/**
 * 侧边栏左下角的数据库管理入口。
 *
 * 点击后把主显示区切换到应用内的数据库管理页（[com.zxce.ai.ui.view.database.DatabasePage]），
 * 不打开浏览器；页面直接调用后端同一套 [com.zxce.ai.service.admin.DatabaseAdminService]，
 * 所以也不需要登录。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SideBarDatabaseButton(modifier: Modifier = Modifier) {
    val sideBar = systemUiState.current.sideBar
    val isActive = sideBar.selectedMenu === sideBar.databaseMenu

    TooltipArea(
        modifier = modifier,
        delayMillis = 400,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(14.dp, 0.dp)),
        tooltip = {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFF1F2329),
                elevation = 4.dp,
            ) {
                Text(
                    text = "数据库管理",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    fontSize = 12.sp,
                )
            }
        },
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = if (isActive) Color(0xFF0B59F7) else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                )
                .clickable { sideBar.selectedMenu = sideBar.databaseMenu },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = sideBar.databasePageIcon,
                contentDescription = "数据库管理",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}
