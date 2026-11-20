package io.github.mangi.eta.ui.screens.skills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaTopLayer
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.components.coui.CouiItem
import io.github.mangi.eta.ui.components.coui.CouiMenu
import io.github.mangi.eta.ui.components.coui.CouiMenuItem
import io.github.mangi.eta.ui.components.coui.CouiPageDefaults
import io.github.mangi.eta.ui.components.coui.CouiText
import io.github.mangi.eta.ui.components.coui.CouiTextKind
import io.github.mangi.eta.ui.components.coui.couiMenuAnchor
import io.github.mangi.eta.ui.components.coui.rememberCouiMenuAnchor
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.suqi8.coui.kmp.basic.Switch as CouiSwitch
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.More
import io.github.suqi8.coui.kmp.theme.COUITheme as CouiTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassPopup
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Delete
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.More
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
            val colors = CouiPageDefaults.themeColors()
            val destructiveColor = CouiTheme.colorScheme.error
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
            CouiItem(
                enabled = enabled,
                onClick = { onToggle(!skill.enabled) },
                title = {
                    CouiText(
                        text = skill.name,
                        kind = CouiTextKind.ItemTitle,
                        color = if (enabled) colors.title else colors.disabled,
                    )
                },
                summary = {
                    CouiText(
                        text = description,
                        kind = CouiTextKind.ItemSummary,
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
                                imageVector = COUIIcons.More,
                                contentDescription = stringResource(R.string.skills_more_named, skill.name),
                                modifier = Modifier.size(20.dp),
                                tint = colors.summary,
                            )
                        }
                        CouiMenu(
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
                        enabled = enabled,
                    )
                },
            )
        }
        AppearanceUiStyle.MIUIX -> {
            var showMenu by remember { mutableStateOf(false) }
            var moreBounds by remember { mutableStateOf(Rect.Zero) }
            val overlayBackdrop = currentGlassOverlayBackdrop()
            BasicComponent(
                enabled = enabled,
                onClick = { onToggle(!skill.enabled) },
                endActions = {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .onGloballyPositioned { moreBounds = it.boundsInWindow() }
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { showMenu = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Glass.More,
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
            EtaTopLayer {
                GlassPopup(
                    show = showMenu,
                    onDismissRequest = { showMenu = false },
                    anchorBounds = moreBounds,
                    backdrop = overlayBackdrop,
                ) {
                    GlassPopupItem(
                        text = stringResource(R.string.ui_view_description),
                        icon = MiuixIcons.Glass.Info,
                        onClick = {
                            showMenu = false
                            showDescription = true
                        },
                    )
                    if (onDelete != null) {
                        HorizontalDivider(
                            modifier = Modifier.padding(
                                horizontal = GlassPopupDefaults.DividerPaddingHorizontal,
                                vertical = GlassPopupDefaults.DividerPaddingVertical,
                            ),
                            thickness = GlassPopupDefaults.DividerThickness,
                        )
                        GlassPopupItem(
                            text = stringResource(R.string.ui_delete_3755f5),
                            icon = MiuixIcons.Glass.Delete,
                            enabled = enabled,
                            contentColor = MiuixTheme.colorScheme.error,
                            onClick = {
                                showMenu = false
                                if (enabled) onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
    ItemDescriptionDialog(
        show = showDescription,
        title = skill.name,
        description = description,
        onDismiss = { showDescription = false },
    )
}
