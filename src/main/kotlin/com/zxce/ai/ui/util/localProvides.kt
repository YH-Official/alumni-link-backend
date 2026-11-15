package com.zxce.ai.ui.util

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState

/**
 * 未分类的 Provides
 * @author 洛洛希雅Lolosia
 * @since 2024-10-20 21:22
 */


/** 当前上下文中的 WindowScope */
val LocalWindowScope = compositionLocalOf<WindowScope> { error("No WindowScope provided") }
val LocalWindowState = compositionLocalOf<WindowState> { error("No WindowState provided") }
