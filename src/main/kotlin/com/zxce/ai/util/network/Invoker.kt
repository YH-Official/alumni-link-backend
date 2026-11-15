package com.zxce.ai.util.network

/**
 * Invoker
 * @author 洛洛希雅Lolosia
 * @since 2024-10-26 18:53
 */
interface Invoker {
    operator fun invoke(vararg args: Any?)
}