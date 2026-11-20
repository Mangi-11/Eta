package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.components.coui.CouiPageDefaults
import io.github.mangi.eta.ui.components.coui.CouiTextField
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextField

@Composable
internal fun SettingsTextField(
    label: String,
    value: String,
    secret: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    inputTransformation: InputTransformation? = if (singleLine) TrimWhitespace else null,
    onChange: (String) -> Unit,
) {
    var visible by rememberSaveable(label) { mutableStateOf(false) }
    val fieldState = rememberDraftTextFieldState(value, onChange)
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val colors = CouiPageDefaults.themeColors()
            CouiTextField(
                state = fieldState,
                inputTransformation = inputTransformation,
                label = label,
                singleLine = singleLine,
                enabled = enabled,
                secretVisible = if (secret) visible else null,
                trailingIcon = if (secret) {
                    {
                        Box(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .size(28.dp)
                                .clickable(enabled = enabled, role = Role.Button) { visible = !visible },
                            contentAlignment = Alignment.Center,
                        ) {
                            CouiIcon(
                                imageVector = if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                contentDescription = stringResource(
                                    if (visible) R.string.page_hide_bb0e7e else R.string.page_show_71b677,
                                ),
                                modifier = Modifier.size(20.dp),
                                tint = if (enabled) colors.summary else colors.disabled,
                            )
                        }
                    }
                } else {
                    null
                },
            )
        }
        AppearanceUiStyle.MIUIX -> {
            if (secret) {
                MiuixSecureTextField(
                    state = fieldState,
                    inputTransformation = inputTransformation,
                    label = label,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                    visible = visible,
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                imageVector = if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                contentDescription = stringResource(
                                    if (visible) R.string.page_hide_bb0e7e else R.string.page_show_71b677,
                                ),
                            )
                        }
                    },
                )
            } else {
                TextField(
                    state = fieldState,
                    inputTransformation = inputTransformation,
                    label = label,
                    lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
