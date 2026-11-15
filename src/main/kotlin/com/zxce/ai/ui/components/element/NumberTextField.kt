package com.zxce.ai.ui.components.element

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType

/**
 * NumberTextField
 * @author 洛洛希雅Lolosia
 * @since 2024-10-29 19:31
 */

@Composable
fun NumberTextField(number: Int, onValueChange: (Int) -> Unit) {

    var value by remember { mutableStateOf(number.toString()) }
    var ok by remember { mutableStateOf(true) }

    TextField(
        modifier = Modifier.onFocusChanged {
            if (!it.isFocused) {
                value = number.toString()
                ok = true
            }
        },
        value = value,
        isError = !ok,
        singleLine = true,
        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
        colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent),
        onValueChange = a@{
            value = it

            if ("^-?\\d+$".toRegex().matches(it)) {
                onValueChange(it.toInt())
                ok = true
            } else {
                ok = false
            }
        }
    )
}