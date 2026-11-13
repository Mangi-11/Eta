package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.input.CouiHapticFeedback
import com.itos.coui.input.performCouiHapticFeedback
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.switch.CouiSwitchDefaults
import com.itos.coui.switch.Switch as CouiSwitch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.components.iconForTool
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.actualToolName
import io.github.mangi.eta.ui.model.toolCardAction
import io.github.mangi.eta.ui.model.toolCardRequirement
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ToolSwitchRow(
    tool: ToolItemUi,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onAction: (AgentToolsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDescription by remember(tool.id) { mutableStateOf(false) }
    val description = if (!rootGranted && tool.id == "terminal") {
        stringResource(R.string.capability_terminal_ordinary_summary)
    } else {
        tool.summary
    }
    val requirementText = toolRequirementText(tool.id, rootGranted, capabilities)
    val action = toolCardAction(tool.id, capabilities)
    val actionLabel = when (action) {
        AgentToolsAction.OpenWebToolsSettings -> stringResource(R.string.web_tools_configure_action)
        AgentToolsAction.OpenBrowser -> stringResource(R.string.action_open_browser)
        AgentToolsAction.OpenPermissions -> stringResource(R.string.tools_manage_permissions)
        AgentToolsAction.OpenEnhancements -> null
        else -> null
    }
    val iconVector = iconForTool(tool.id)

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            val dark = CouiTheme.isDark
            val colors = CouiPageDefaults.themeColors(dark)
            val view = LocalView.current
            val interactionSource = remember { MutableInteractionSource() }
            var optimisticChecked by remember(checked) { mutableStateOf(checked) }
            val commitToggle: (Boolean) -> Unit = { target ->
                optimisticChecked = target
                onCheckedChange(target)
            }
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(min = CouiPageDefaults.rowMinHeight)
                    .couiButtonFeedback(dark, interactionSource, enabled = true, list = true)
                    .toggleable(
                        value = optimisticChecked,
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = true,
                        role = Role.Switch,
                        onValueChange = { target ->
                            view.performCouiHapticFeedback(CouiHapticFeedback.Switch, true)
                            commitToggle(target)
                        },
                    )
                    .padding(
                        horizontal = CouiPageDefaults.rowPadding,
                        vertical = 12.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = CouiPageDefaults.iconSpacing)
                        .size(CouiPageDefaults.iconSize),
                    contentAlignment = Alignment.Center,
                ) {
                    CouiIcon(
                        imageVector = iconVector,
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                        tint = if (optimisticChecked) colors.accent else colors.summary.copy(alpha = 0.65f),
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CouiText(
                            text = tool.title,
                            kind = CouiTextKind.ItemTitle,
                            color = colors.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (requirementText != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.title.copy(alpha = 0.06f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                CouiText(
                                    text = requirementText,
                                    kind = CouiTextKind.GroupFooter,
                                    color = colors.summary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    CouiText(
                        text = description,
                        kind = CouiTextKind.ItemSummary,
                        color = colors.summary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (action != null && actionLabel != null) {
                        Row(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(role = Role.Button) { onAction(action) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            CouiText(
                                text = actionLabel,
                                kind = CouiTextKind.GroupFooter,
                                color = colors.accent,
                                maxLines = 1,
                            )
                            CouiIcon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = colors.accent,
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) { showDescription = true },
                    contentAlignment = Alignment.Center,
                ) {
                    CouiIcon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = stringResource(R.string.ui_description_named, tool.title),
                        modifier = Modifier.size(16.dp),
                        tint = colors.summary.copy(alpha = 0.6f),
                    )
                }

                Spacer(Modifier.width(8.dp))

                CouiSwitch(
                    checked = optimisticChecked,
                    onCheckedChange = null,
                    dark = dark,
                    colors = CouiSwitchDefaults.colors(colors.accent, dark),
                    enabled = true,
                    interactionSource = interactionSource,
                    onDragCheckedChange = commitToggle,
                )
            }
        }

        AppearanceUiStyle.MIUIX -> {
            BasicComponent(
                modifier = modifier,
                onClick = { onCheckedChange(!checked) },
                role = Role.Switch,
                startAction = {
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = iconVector,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (checked) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.65f)
                            },
                        )
                    }
                },
                endActions = {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { showDescription = true }
                            .align(Alignment.CenterVertically),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = stringResource(R.string.ui_description_named, tool.title),
                            modifier = Modifier.size(16.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantActions.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = checked,
                        onCheckedChange = onCheckedChange,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = tool.title,
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (requirementText != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = requirementText,
                                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Text(
                    text = description,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (action != null && actionLabel != null) {
                    Row(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button) { onAction(action) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = actionLabel,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    if (showDescription) {
        ItemDescriptionDialog(
            title = tool.title,
            description = listOfNotNull(description, requirementText).joinToString("\n\n"),
            onDismiss = { showDescription = false },
        )
    }
}

@Composable
private fun toolRequirementText(id: String, rootGranted: Boolean, capabilities: AgentToolCapabilities): String? {
    val requirement = toolCardRequirement(id)
    val unavailableCode = capabilities.unavailableCode(actualToolName(id))
    return when {
        !rootGranted && requirement.rootRequirement == RootRequirement.REQUIRED -> stringResource(R.string.capability_root_required)
        !capabilities.accessibilityAvailable && requirement.accessibility -> stringResource(R.string.capability_accessibility_required)
        unavailableCode == "NOTIFICATION_ACCESS_REQUIRED" -> stringResource(R.string.capability_notification_access_required)
        unavailableCode == "APP_USAGE_ACCESS_REQUIRED" -> stringResource(R.string.capability_usage_access_required)
        unavailableCode == "LOCATION_PERMISSION_REQUIRED" -> stringResource(R.string.capability_location_access_required)
        requirement.colorOs -> stringResource(R.string.capability_coloros_required)
        !rootGranted && requirement.rootRequirement == RootRequirement.PARTIAL -> stringResource(R.string.capability_root_partial)
        else -> null
    }
}
