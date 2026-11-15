package com.zxce.ai.ui.view.setting.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zxce.ai.ui.util.LocalWindowScope
import io.github.vinceglb.filekit.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.core.FileKitPlatformSettings
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

@Composable
fun SettingFileItem(text: String, path: String?, onSelect: (path: String?) -> Unit) {
    val window = LocalWindowScope.current.window
    var path1 = Path("").absolutePathString()
    try {
        if (path != null && Path(path).exists()) path1 = path
    } catch (_: Throwable) {
    }

    val launcher = rememberDirectoryPickerLauncher(
        "请选择文件夹",
        initialDirectory = path1,
        platformSettings = FileKitPlatformSettings(window)
    ) {
        onSelect(it?.path)
    }

    SettingItem(text) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                path ?: "请选择文件夹",
                modifier = Modifier.weight(1f).padding(5.dp),
                color = if (path.isNullOrEmpty()) Color.LightGray else Color.Black,
                textAlign = TextAlign.End
            )
            Button({ launcher.launch() }) {
                Text("选择")
            }
        }
    }
}