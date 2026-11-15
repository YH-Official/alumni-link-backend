package com.zxce.ai.ui.layout.normal.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.zxce.ai.PROJECT_NAME

@Composable
fun SideBar() {
    Column (
        modifier = Modifier
            .fillMaxSize()
            //.border(1.dp, MaterialTheme.colors.primary, RectangleShape)
            .background(MaterialTheme.colors.primary)
    ) {
        Box(
            modifier = Modifier
                .width(50.dp)
                .fillMaxWidth()
                .padding(0.dp, 20.dp)
                .background(MaterialTheme.colors.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                PROJECT_NAME.split("").joinToString("\n"),
                color = MaterialTheme.colors.background,
                fontSize = 1.5.em,
                fontWeight = FontWeight.Bold,
                lineHeight = 1.6.em
            )
        }
        SideBarMenu()
    }
}