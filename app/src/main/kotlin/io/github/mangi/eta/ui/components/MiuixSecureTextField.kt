package io.github.mangi.eta.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextFieldColors
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MiuixSecureTextField(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    visible: Boolean = false,
    enabled: Boolean = true,
    colors: TextFieldColors = TextFieldDefaults.textFieldColors(),
    inputTransformation: InputTransformation? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicSecureTextField(
        state = state,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
        enabled = enabled,
        interactionSource = interaction,
        inputTransformation = inputTransformation,
        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
        textStyle = MiuixTheme.textStyles.main.copy(color = MiuixTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(colors.borderColor),
        decorator = { inner ->
            Row(
                modifier = Modifier
                    .squircleBackground(color = colors.backgroundColor, cornerRadius = TextFieldDefaults.CornerRadius)
                    .squircleBorder(
                        width = if (focused) 2.dp else 0.dp,
                        color = if (focused) colors.borderColor else colors.backgroundColor,
                        cornerRadius = TextFieldDefaults.CornerRadius,
                    )
                    .padding(horizontal = TextFieldDefaults.InsideMargin.width, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = label, style = MiuixTheme.textStyles.footnote1, color = colors.labelColor)
                    inner()
                }
                trailingIcon?.invoke()
            }
        },
    )
}
