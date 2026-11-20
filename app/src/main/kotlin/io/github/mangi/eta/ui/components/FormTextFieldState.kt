package io.github.mangi.eta.ui.components

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

/** Store 持有可保存的草稿，编辑器独占选区和输入法组合区。相同文本回写时不能重置编辑器。 */
@Composable
internal fun rememberDraftTextFieldState(value: String, onEdit: (String) -> Unit): TextFieldState {
    val state = remember { TextFieldState(value) }
    val currentValue by rememberUpdatedState(value)
    val currentOnEdit by rememberUpdatedState(onEdit)
    LaunchedEffect(state) {
        var previousValue = value
        var lastReported = value
        snapshotFlow { currentValue to state.text.toString() }.collect { (model, text) ->
            val replacedExternally = model != previousValue && model != lastReported
            previousValue = model
            if (replacedExternally) {
                lastReported = model
                if (text != model) state.setTextAndPlaceCursorAtEnd(model)
            } else if (text != lastReported) {
                lastReported = text
                currentOnEdit(text)
            }
        }
    }
    return state
}

internal val DecimalDigits = InputTransformation {
    for (index in length - 1 downTo 0) {
        if (!asCharSequence()[index].isDigit()) delete(index, index + 1)
    }
}

internal val TrimWhitespace = InputTransformation {
    val text = asCharSequence()
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start < 0) {
        delete(0, length)
    } else {
        val end = text.indexOfLast { !it.isWhitespace() } + 1
        if (end < length) delete(end, length)
        if (start > 0) delete(0, start)
    }
}
