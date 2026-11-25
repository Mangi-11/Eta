package io.github.mangi.eta.ui.screens.providers

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.SecureTextField
import io.github.mangi.eta.ui.components.rememberDraftTextFieldState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

internal fun LazyListScope.providerHeadersEditor(
    headers: List<ProviderHeaderDraft>,
    onHeadersChange: (List<ProviderHeaderDraft>) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    item(key = "custom_headers") {
        ProviderHeadersSection(
            headers = headers,
            onHeadersChange = onHeadersChange,
            expanded = expanded,
            onExpandedChange = onExpandedChange,
        )
    }
}

@Composable
internal fun ProviderHeadersSection(
    headers: List<ProviderHeaderDraft>,
    onHeadersChange: (List<ProviderHeaderDraft>) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    ProviderSection(title = stringResource(R.string.provider_i18n_custom_headers)) {
        val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f)
        val headersCountTitle = if (headers.isEmpty()) {
            stringResource(R.string.provider_i18n_headers_unset)
        } else {
            pluralStringResource(R.plurals.provider_i18n_headers_count, headers.size, headers.size)
        }
        val headersSummary = stringResource(R.string.provider_i18n_headers_summary)
        val toggleDescription = stringResource(if (expanded) R.string.speech_collapse else R.string.speech_expand)
        val addHeaderTitle = stringResource(R.string.provider_i18n_add_header)
        BasicComponent(
            title = headersCountTitle,
            summary = headersSummary,
            endActions = {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = toggleDescription,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    modifier = Modifier.rotate(chevronRotation),
                )
            },
            onClick = { onExpandedChange(!expanded) },
        )
        if (expanded) {
            headers.forEach { row ->
                key(row.id) {
                    ProviderHeaderRow(
                        row = row,
                        onNameChange = { value ->
                            onHeadersChange(headers.map {
                                if (it.id == row.id) it.copy(header = it.header.copy(name = value)) else it
                            })
                        },
                        onValueChange = { value ->
                            onHeadersChange(headers.map {
                                if (it.id == row.id) it.copy(header = it.header.copy(value = value)) else it
                            })
                        },
                        onRemove = { onHeadersChange(headers.filterNot { it.id == row.id }) },
                    )
                }
            }
            BasicComponent(
                title = addHeaderTitle,
                titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.primary),
                startAction = {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
                onClick = { onHeadersChange(headers + ProviderHeaderDraft()) },
            )
        }
    }
}

@Composable
private fun ProviderHeaderRow(
    row: ProviderHeaderDraft,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val nameState = rememberDraftTextFieldState(row.header.name, onNameChange)
    val valueState = rememberDraftTextFieldState(row.header.value, onValueChange)
    var visible by remember(row.id) { mutableStateOf(false) }
    val nameLabel = stringResource(R.string.provider_i18n_header_name)
    val valueLabel = stringResource(R.string.provider_i18n_header_value)
    Row(
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                state = nameState,
                lineLimits = TextFieldLineLimits.SingleLine,
                label = nameLabel,
                modifier = Modifier.fillMaxWidth(),
            )
            SecureTextField(
                state = valueState,
                label = valueLabel,
                visible = visible,
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            imageVector = if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                            contentDescription = if (visible) {
                                stringResource(R.string.page_hide_bb0e7e)
                            } else {
                                stringResource(R.string.page_show_71b677)
                            },
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.ui_delete_3755f5),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}
