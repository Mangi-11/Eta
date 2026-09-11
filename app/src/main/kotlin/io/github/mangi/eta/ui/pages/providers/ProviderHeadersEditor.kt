package io.github.mangi.eta.ui.pages.providers

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
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
    // 请求头数量很少且必须收进同一张卡片，折叠/展开态整组重排，不拆成独立 Lazy 条目。
    item(key = "custom_headers") {
        ProviderSection(title = "自定义请求头") {
            val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f)
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
                    HorizontalDivider()
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
                HorizontalDivider()
                BasicComponent(
                    title = "添加请求头",
                    titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.primary),
                    startAction = {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    },
                    onClick = { onHeadersChange(headers + ProviderHeaderDraft()) },
                )
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
    val context = LocalContext.current
    var visible by remember(row.id) { mutableStateOf(false) }
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
                                context.getString(R.string.page_hide_bb0e7e)
                            } else {
                                context.getString(R.string.page_show_71b677)
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
                contentDescription = context.getString(R.string.ui_delete_3755f5),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
    }
}
