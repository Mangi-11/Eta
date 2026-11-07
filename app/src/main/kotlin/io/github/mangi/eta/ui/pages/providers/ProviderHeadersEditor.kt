package io.github.mangi.eta.ui.pages.providers

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    ProviderSection(title = "自定义请求头") {
        val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f)
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                val colors = CouiPageDefaults.themeColors()
                CouiPreference(
                    title = if (headers.isEmpty()) "未设置" else "已设置 ${headers.size} 项",
                    summary = "可覆盖 User-Agent；认证与传输请求头由系统管理。",
                    showChevron = false,
                    trailing = {
                        CouiIcon(
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = if (expanded) "收起" else "展开",
                            tint = colors.summary,
                            modifier = Modifier
                                .size(22.dp)
                                .rotate(chevronRotation),
                        )
                    },
                    onClick = { onExpandedChange(!expanded) },
                )
                if (expanded) {
                    headers.forEach { row ->
                        Divider()
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
                    Divider()
                    CouiPreference(
                        title = "添加请求头",
                        titleColor = colors.accent,
                        showChevron = false,
                        onClick = { onHeadersChange(headers + ProviderHeaderDraft()) },
                    )
                }
            }
            AppearanceUiStyle.MIUIX -> {
                BasicComponent(
                    title = if (headers.isEmpty()) "未设置" else "已设置 ${headers.size} 项",
                    summary = "可覆盖 User-Agent；认证与传输请求头由系统管理。",
                    endActions = {
                        Icon(
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = if (expanded) "收起" else "展开",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            modifier = Modifier.rotate(chevronRotation),
                        )
                    },
                    onClick = { onExpandedChange(!expanded) },
                )
                if (expanded) {
                    headers.forEach { row ->
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
                    BasicComponent(
                        title = "添加请求头",
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
    }
}

@Composable
private fun ProviderHeaderRow(
    row: ProviderHeaderDraft,
    onNameChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var visible by remember(row.id) { mutableStateOf(false) }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val colors = CouiPageDefaults.themeColors()
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CouiTextField(
                        value = row.header.name,
                        onValueChange = onNameChange,
                        label = "名称",
                        singleLine = true,
                    )
                    CouiTextField(
                        value = row.header.value,
                        onValueChange = onValueChange,
                        label = "值",
                        singleLine = true,
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Box(
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .size(28.dp)
                                    .clickable(role = Role.Button) { visible = !visible },
                                contentAlignment = Alignment.Center,
                            ) {
                                CouiIcon(
                                    imageVector = if (visible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                    contentDescription = stringResource(
                                        if (visible) R.string.page_hide_bb0e7e else R.string.page_show_71b677,
                                    ),
                                    modifier = Modifier.size(20.dp),
                                    tint = colors.summary,
                                )
                            }
                        },
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(role = Role.Button, onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    CouiIcon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.ui_delete_3755f5),
                        modifier = Modifier.size(22.dp),
                        tint = colors.summary,
                    )
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextField(
                        value = row.header.name,
                        onValueChange = onNameChange,
                        label = "名称",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = row.header.value,
                        onValueChange = onValueChange,
                        label = "值",
                        singleLine = true,
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
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
    }
}
