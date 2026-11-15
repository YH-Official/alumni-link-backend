package com.zxce.ai.ui.view.index.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.seconds

@Composable
fun MultiCard(vararg cards: @Composable () -> Unit) {
    val show = Modifier.alpha(1f)
    val hide = Modifier.alpha(0f)
    var index by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(5.seconds)
            index = (index + 1) % cards.size
        }
    }
    for ((i, block) in cards.withIndex()) {
        val modifier = if (i == index) show
        else hide
        Box(modifier) {
            block()
        }
    }
}