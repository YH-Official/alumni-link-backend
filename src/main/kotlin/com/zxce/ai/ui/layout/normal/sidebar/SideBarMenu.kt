package com.zxce.ai.ui.layout.normal.sidebar

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zxce.ai.ui.store.systemUiState

/**
 * 侧边栏菜单控件
 * @author 洛洛希雅Lolosia
 * @since 2024-10-20 21:02
 */

@Preview
@Composable
fun SideBarMenu(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(bottom = 10.dp), // 填充父容器
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {

        val sideBar = systemUiState.current.sideBar
        // 列出所有菜单，将其绘制到菜单列表区域内
        for (menuItem in sideBar.allMenus) {
            // 菜单是分割线类型
            if (menuItem.isSplitter) {
                Divider(
                    color = Color(0xFF9E9b99), // 分割线颜色
                    thickness = 1.dp,   // 分割线厚度
                    modifier = Modifier.padding(vertical = 5.dp)
                )
            }
            // 普通菜单
            else SidebarMenuItem(
                menuItem,
                modifier = Modifier.padding(top = 5.dp),
                isActive = menuItem === sideBar.selectedMenu,
                onClick = {
                    sideBar.selectedMenu = menuItem
                }
            )
        }
    }
}

