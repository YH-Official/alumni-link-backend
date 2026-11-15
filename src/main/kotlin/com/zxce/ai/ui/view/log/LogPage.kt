package com.zxce.ai.ui.view.log

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.filter.ThresholdFilter
import com.zxce.ai.logging.CoreLogging
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.lang.reflect.Field
import kotlin.reflect.KProperty

@Composable
fun LogPage() {
    LogView(LOGS)
}

/**
 * 为日志页面注册日志钩子
 */
fun registerLogListener() {
    registerLogListener(LOGS)
}

private val LOGS = mutableStateListOf<Log>()

@Composable
private fun LogView(logs: SnapshotStateList<Log>) {
    Box(
        modifier = Modifier.fillMaxSize()
            .padding(bottom = 20.dp)
    ) {
        val coroutineScope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        var autoScroll by remember { mutableStateOf(true) }
        var rec by remember { mutableStateOf<Log?>(null) }
        var copyRec by remember { mutableStateOf<List<Log>>(emptyList()) }
        val nowRec = logs.lastOrNull()
        if (rec !== nowRec) {
            rec = nowRec
            copyRec = synchronized(logs) {
                logs.toList()
            }
            coroutineScope.launch {
                if (autoScroll) {
                    listState.animateScrollToItem(logs.size, 3)
                }
            }
        }

        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .padding(5.dp)
                    .fillMaxSize()
            ) {
                items(copyRec) { (type, it) ->
                    if (type != LogType.FATAL) {
                        val color = when (type) {
                            LogType.TRACE -> Color(0xFFAA7FAE)
                            LogType.DEBUG -> Color.Blue
                            LogType.ERROR -> Color.Red
                            LogType.WARN -> Color(0xFFFC7D01)
                            else -> Color.Black
                        }
                        Text(
                            it,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 0.9.em,
                            color = color,
                            softWrap = true
                        )
                    } else {
                        Text(
                            it,
                            modifier = Modifier
                                .background(Color.Black)
                                .fillMaxWidth(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 0.9.em,
                            color = Color.White,
                            softWrap = true
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .background(Color(0x66000000))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp)
            ) {
                Checkbox(
                    checked = autoScroll,
                    onCheckedChange = { autoScroll = it },
                    colors = CheckboxDefaults.colors(
                        uncheckedColor = Color.Gray,
                    )
                )
                Text("自动滚动", color = Color.White, modifier = Modifier.padding(end = 10.dp))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp)
            ) {
                Text("日志等级", color = Color.White, modifier = Modifier.padding(end = 10.dp))
                LogLevelDropDown()
            }
        }
    }
}

/**
 * 日志级别选择器下拉框
 */
@Composable
private fun LogLevelDropDown() {
    val logLevels = remember { LogType.entries.map { it.name } + listOf("ALL", "OFF") }
    var show by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<ThresholdFilter?>(null) }
    var root by remember { mutableStateOf<Logger?>(null) }
    var field by remember { mutableStateOf<Field?>(null) }
    // 将日志级别访问器委托给代理。
    var level by object {
        operator fun getValue(it: Nothing?, prop: KProperty<Any?>): Level {
            return field?.get(filter) as? Level ?: Level.INFO
        }

        operator fun setValue(it: Nothing?, prop: KProperty<Any?>, value: Level) {
            root!!.level = if (value.levelInt > Level.INFO_INT) Level.INFO
            else value

            filter!!.setLevel(value.levelStr)
        }
    }

    // 通过反射初始化过滤器的字段
    LaunchedEffect(Unit) {
        val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
        root = ctx.getLogger("ROOT")
        val appender = root!!.getAppender("LISTENER")
        filter = appender.copyOfAttachedFiltersList.filterIsInstance<ThresholdFilter>().first()
        field = ThresholdFilter::class.java.getDeclaredField("level")
        field!!.isAccessible = true
    }

    Button(onClick = { show = true }) {
        Text(level.levelStr)
    }

    DropdownMenu(expanded = show, onDismissRequest = { show = false }) {
        logLevels.forEachIndexed { _, logLevel ->
            DropdownMenuItem(onClick = {
                level = Level.toLevel(logLevel)
                show = false
            }) {
                Text(logLevel)
            }
        }
    }
}


@Preview
@Composable
private fun PreLogView() = LogView(
    mutableStateListOf(
        Log(LogType.TRACE, "Hello World"),
        Log(LogType.DEBUG, "Hello World"),
        Log(LogType.INFO, "Hello World"),
        Log(LogType.WARN, "Hello World"),
        Log(LogType.ERROR, "Hello World"),
        Log(LogType.FATAL, "Hello World")
    )
)

private data class Log(val type: LogType, val data: String)
private enum class LogType {
    TRACE, DEBUG, INFO, WARN, ERROR, FATAL
}

private fun registerLogListener(logs: SnapshotStateList<Log>) {
    var type = LogType.INFO
    fun add(line: String) {
        if (line.length > 16) {
            type = when (line.slice(10..16).trim()) {
                "[TRACE]" -> LogType.TRACE
                "[DEBUG]" -> LogType.DEBUG
                "[INFO]" -> LogType.INFO
                "[WARN]" -> LogType.WARN
                "[ERROR]" -> LogType.ERROR
                "[FATAL]" -> LogType.FATAL
                else -> type
            }
        }
        synchronized(logs) {
            logs += Log(type, line.replace("\t", "    "))
            while (logs.size > 200) {
                logs.removeFirst()
            }
        }
    }

    CoreLogging.listener += {
        var str = it
        while ('\n' in str) {
            val index = str.indexOf('\n')
            val line = str.substring(0 until index)
            str = str.substring(index + 1)
            add(line)
        }
        if (str.isNotEmpty()) add(str)
    }
}