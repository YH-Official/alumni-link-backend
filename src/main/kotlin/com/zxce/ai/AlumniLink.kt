package com.zxce.ai

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.application
import com.zxce.ai.ui.components.tray.SystemTrayIcon
import com.zxce.ai.ui.store.systemUiState
import com.zxce.ai.ui.view.log.registerLogListener
import com.zxce.ai.ui.window.InitializationWindow
import com.zxce.ai.ui.window.MainWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.ConfigurableApplicationContext
import java.awt.GraphicsEnvironment

/**
 * 校友录后端应用主入口点
 * @author 洛洛希雅Lolosia
 * @since 2024-08-19 11:03
 */
object AlumniLink {
    @SpringBootApplication
    class Application

    lateinit var applicationContext: ConfigurableApplicationContext
        private set

    /** Kotlin Compose 的 ApplicationScope，只有退出程序的功能。*/
    private lateinit var applicationScope: ApplicationScope
    internal val applicationInitialized = mutableStateOf(false)

    @JvmStatic
    fun main(args: Array<String>) {
        val noGui = args.contains("--no-gui") || GraphicsEnvironment.isHeadless()
        if (!noGui) app(args)
        else applicationContext = runApplication<Application>(*args)
    }

    private fun app(args: Array<String>) = application {
        applicationScope = this

        val mainWindow = systemUiState.current.mainWindow

        LaunchedEffect(Unit){
            registerLogListener()
            withContext(Dispatchers.Default){
                applicationContext = runApplication<Application>(*args)
                applicationInitialized.value = true
            }
            mainWindow.isShow = true
        }

        // 保活，不要关闭这个窗口
        InitializationWindow()
        SystemTrayIcon()

        if (systemUiState.current.mainWindow.isShow){
            MainWindow()
        }
    }

    /**
     * 退出应用程序
     */
    suspend fun exitApplication() {
        // 将应用程序设置到退出界面
        applicationInitialized.value = false
        // 关闭 Spring Boot
        withContext(Dispatchers.Default) {
            applicationContext.stop()
            // while (ctx.isActive) delay(250)
        }

        // 程序退出
        applicationScope.exitApplication()
    }
}