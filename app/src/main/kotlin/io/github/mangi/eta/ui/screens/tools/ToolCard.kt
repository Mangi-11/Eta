package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.ui.components.EtaFeatureCard
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.components.iconForTool
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.actualToolName
import io.github.mangi.eta.ui.model.toolCardAction
import io.github.mangi.eta.ui.model.toolCardRequirement
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ToolCard(
    tool: ToolItemUi,
    rootGranted: Boolean,
    shizukuGranted: Boolean = false,
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
    val requirementText = toolRequirementText(tool.id, rootGranted, shizukuGranted, capabilities)
    val action = toolCardAction(tool.id, capabilities)
    val actionText = when (action) {
        AgentToolsAction.OpenBrowser -> stringResource(R.string.action_open_browser)
        AgentToolsAction.OpenPermissions -> stringResource(R.string.tools_manage_permissions)
        AgentToolsAction.OpenEnhancements -> stringResource(R.string.tools_view_enhancements)
        else -> stringResource(R.string.ui_view_description)
    }
    EtaFeatureCard(
        title = tool.title,
        summary = description,
        modifier = modifier,
        onClick = { if (action != null) onAction(action) else showDescription = true },
        icon = { EtaPreferenceIcon(icon = iconForTool(tool.id), tint = EtaPreferenceColors.Green) },
        action = if (action != null) {
            {
                IconButton(onClick = { showDescription = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = stringResource(R.string.ui_description_named, tool.title),
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
        } else null,
    ) {
        requirementText?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = actionText,
                style = MiuixTheme.textStyles.footnote1,
                color = if (action != null) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.weight(1f),
            )
            if (action != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.primary,
                )
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
private fun toolRequirementText(
    id: String,
    rootGranted: Boolean,
    shizukuGranted: Boolean,
    capabilities: AgentToolCapabilities,
): String? {
    val requirement = toolCardRequirement(id)
    val elevated = rootGranted ||
        (shizukuGranted && AgentToolRequirements.shizukuSatisfiedTools.contains(actualToolName(id)))
    val unavailableCode = capabilities.unavailableCode(actualToolName(id))
    return when {
        !elevated && requirement.rootRequirement == RootRequirement.REQUIRED -> stringResource(R.string.capability_root_required)
        !capabilities.accessibilityAvailable && requirement.accessibility -> stringResource(R.string.capability_accessibility_required)
        unavailableCode == "NOTIFICATION_ACCESS_REQUIRED" -> stringResource(R.string.capability_notification_access_required)
        unavailableCode == "APP_USAGE_ACCESS_REQUIRED" -> stringResource(R.string.capability_usage_access_required)
        unavailableCode == "LOCATION_PERMISSION_REQUIRED" -> stringResource(R.string.capability_location_access_required)
        requirement.colorOs -> stringResource(R.string.capability_coloros_required)
        !rootGranted && !shizukuGranted && requirement.rootRequirement == RootRequirement.PARTIAL -> stringResource(R.string.capability_root_partial)
        else -> null
    }
}
