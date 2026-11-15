package com.zxce.ai.ui.view.setting.components

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zxce.ai.ui.components.element.NumberTextField
import com.zxce.ai.util.property.asProperty
import com.zxce.ai.util.property.asState
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty0

@Composable
fun SettingItem(
    text: String,
    kProperty: KMutableProperty0<*>? = null,
    state: MutableState<*>? = null,
    clazz: KClass<*>? = null,
    block: (@Composable () -> Unit)? = null
) {
    @Suppress("UNCHECKED_CAST")
    Row(
        modifier = Modifier.fillMaxWidth().padding(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text)
        block?.invoke()?.let { return }

        var state1: Any? by if (state != null) (state as MutableState<Any?>)
        else if (kProperty != null) remember {
            (kProperty as KMutableProperty0<Any?>).asProperty().asState()
        }
        else return

        val clazz1: KClass<*> = if (clazz != null) clazz
        else if (kProperty != null) kProperty.returnType.classifier as KClass<*>
        else return

        if (state1 == null) {
            Text("<null>")
            return
        }

        when (clazz1) {
            Int::class -> NumberTextField(state1 as Int) { state1 = it }
            Boolean::class -> Switch(state1 as Boolean, onCheckedChange = { state1 = it })
            String::class -> TextField(
                state1 as String,
                colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent),
                onValueChange = { state1 = it }
            )
        }
    }
}

@Preview
@Composable
private fun NumberSettingItemPreview(){
    val state = mutableStateOf(0)
    SettingItem("数字", state = state, clazz = Int::class)
}