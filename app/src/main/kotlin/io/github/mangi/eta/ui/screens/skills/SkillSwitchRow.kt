package io.github.mangi.eta.ui.screens.skills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Item
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.switch.CouiSwitchDefaults
import com.itos.coui.switch.Switch as CouiSwitch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.model.SkillItemUi
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SkillSwitchRow(
    skill: SkillItemUi,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var showDescription by remember(skill.id) { mutableStateOf(false) }
    val description = skill.description.ifBlank { stringResource(R.string.skills_no_description) }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val dark = CouiTheme.isDark
            val colors = CouiPageDefaults.themeColors(dark)
            val destructiveColor = CouiDialogDefaults.themeColors(dark).destructive
            var showMenu by remember { mutableStateOf(false) }
            val anchor = rememberCouiMenuAnchor()
            val viewDescText = stringResource(R.string.ui_view_description)
            val deleteText = stringResource(R.string.ui_delete_3755f5)
            val menuItems = remember(viewDescText, deleteText, onDelete, enabled, destructiveColor) {
                buildList {
                    add(CouiMenuItem(value = 0, text = viewDescText))
                    if (onDelete != null) {
                        add(
                            CouiMenuItem(
                                value = 1,
                                text = deleteText,
                                enabled = enabled,
                                iconTint = destructiveColor,
                                dividerBefore = true,
                            ),
                        )
                    }
                }
            }
            Item(
                enabled = enabled,
                onClick = { onToggle(!skill.enabled) },
                title = {
                    CouiText(
                        text = skill.name,
                        kind = CouiTextKind.PreferenceTitle,
                        color = if (enabled) colors.title else colors.disabled,
                    )
                },
                summary = {
                    CouiText(
                        text = description,
                        kind = CouiTextKind.PreferenceSummary,
                        color = if (enabled) colors.summary else colors.disabled,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailing = {
                    Box {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .couiMenuAnchor(anchor)
                                .clickable(role = Role.Button) {
                                    anchor.open { showMenu = true }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            CouiIcon(
                                imageVector = CouiIcons.More,
                                contentDescription = stringResource(R.string.skills_more_named, skill.name),
                                modifier = Modifier.size(20.dp),
                                tint = colors.summary,
                            )
                        }
                        Menu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            items = menuItems,
                            onItemClick = { item ->
                                showMenu = false
                                when (item.value) {
                                    0 -> showDescription = true
                                    1 -> if (enabled) onDelete?.invoke()
                                }
                            },
                            anchor = anchor,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    CouiSwitch(
                        checked = skill.enabled,
                        onCheckedChange = onToggle,
                        dark = dark,
                        colors = CouiSwitchDefaults.colors(colors.accent, dark),
                        enabled = enabled,
                    )
                },
            )
        }
        AppearanceUiStyle.MIUIX -> {
            BasicComponent(
                enabled = enabled,
                onClick = { onToggle(!skill.enabled) },
                endActions = {
                    OverlayIconDropdownMenu(
                        modifier = Modifier.align(Alignment.CenterVertically),
                        entry = DropdownEntry(
                            items = listOfNotNull(
                                DropdownItem(
                                    text = stringResource(R.string.ui_view_description),
                                    onClick = { showDescription = true },
                                ),
                                onDelete?.let { delete ->
                                    DropdownItem(
                                        text = stringResource(R.string.ui_delete_3755f5),
                                        enabled = enabled,
                                        onClick = { if (enabled) delete() },
                                    )
                                },
                            ),
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreHoriz,
                            contentDescription = stringResource(R.string.skills_more_named, skill.name),
                            modifier = Modifier.size(20.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = onToggle,
                        enabled = enabled,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                },
            ) {
                Text(
                    text = skill.name,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                )
                Text(
                    text = description,
                    style = MiuixTheme.textStyles.body2,
                    color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.disabledOnSecondaryVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
    if (showDescription) {
        ItemDescriptionDialog(
            title = skill.name,
            description = description,
            onDismiss = { showDescription = false },
        )
    }
}
