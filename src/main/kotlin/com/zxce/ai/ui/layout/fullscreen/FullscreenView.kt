package com.zxce.ai.ui.layout.fullscreen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zxce.ai.ui.store.systemUiState


/**
 * FullscreenView
 * @author 洛洛希雅Lolosia
 * @since 2024-10-20 20:53
 */


@Composable
fun FullscreenView(content: @Composable () -> Unit) {
    val blurValue = systemUiState.current.mainWindow.blurValue

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Image(
        //     painter = ImageLoader.loadImageResource("bg.jpg"),
        //     contentDescription = "Background",
        //     modifier = Modifier.fillMaxSize().blur(blurValue.dp),
        //     alignment = Alignment.Center,
        //     contentScale = ContentScale.Crop,
        // )

        Row(
            modifier = Modifier.fillMaxSize()
        ) {
            content()
        }
    }
}