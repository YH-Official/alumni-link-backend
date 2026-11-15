package com.zxce.ai.ui.layout.normal.sidebar

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.zxce.ai.ui.store.SystemUiState
import com.zxce.ai.ui.store.systemUiState

/**
 * 侧边栏菜单条目
 *
 * @author 洛洛希雅Lolosia
 * @since 2024-10-20 21:03
 */
@Composable
fun SidebarMenuItem(
    menuItem: SystemUiState.SideBarMenuItem,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    onClick: () -> Unit = {}
) {
    val box = @Composable { m: Modifier ->
        Box(
            modifier = m
                .clickable { onClick() }
                .height(40.dp)
                .width(40.dp)
                .background(
                    if (!isActive) Color.Transparent
                    else Color(0xFF0B59F7)
                )
        ) {
            Box(modifier = Modifier.size(30.dp).align(Alignment.Center)) {
                menuItem.painter?.let {
                    Image(
                        painter = it,
                        contentDescription = "Background",
                        alignment = Alignment.Center,
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
    if (isActive) Surface(modifier, shape = RoundedCornerShape(10.dp)) {
        box(Modifier)
    }
    else {
        box(modifier)
    }
}

@Preview
@Composable
private fun SidebarMenuItemPreview() {
    val item = object : SystemUiState.SideBarMenuItem {
        override val title = ""
        override val painter = systemUiState.current.sideBar.homePageIcon
        override val isSplitter = false
        override val show = true
        override val component = null
    }
    Column (Modifier.background(Color.Black).fillMaxSize()) {
        SidebarMenuItem(item, isActive = true)
        Box(Modifier.height(10.dp)) {  }
        SidebarMenuItem(item)
    }
}