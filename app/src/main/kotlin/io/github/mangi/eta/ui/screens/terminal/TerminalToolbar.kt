package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.ui.state.displayName
import io.github.mangi.eta.ui.components.StatusSuccess
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 终端两种视图互相切换的目标；工具栏只显示目标模式的图标，避免文字按钮挤占空间。 */
internal enum class TerminalModeTarget { CONSOLE, BLOCKS }

/**
 * 块式终端与控制台共用的工具栏：左侧环境分段切换，右侧会话、守护任务与视图切换图标。
 * 页面标题与返回由应用壳层持有，这里不重复标题。
 */
@Composable
internal fun TerminalToolbar(
    environment: TerminalEnvironment,
    linuxEnvironment: TerminalEnvironment,
    onSwitchEnvironment: (TerminalEnvironment) -> Unit,
    sessionCount: Int,
    daemonRunning: Boolean,
    onOpenSessions: () -> Unit,
    onOpenTasks: () -> Unit,
    modeTarget: TerminalModeTarget?,
    onSwitchMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EnvironmentSegments(
            options = listOf(TerminalEnvironment.ANDROID, linuxEnvironment),
            selected = environment,
            onSelect = onSwitchEnvironment,
        )
        Spacer(modifier = Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarIcon(
                icon = Icons.Rounded.Layers,
                contentDescription = stringResource(R.string.terminal_sessions),
                badge = sessionCount.takeIf { it > 1 }?.toString(),
                onClick = onOpenSessions,
            )
            ToolbarIcon(
                icon = Icons.Rounded.Insights,
                contentDescription = stringResource(R.string.terminal_daemon_tasks),
                dot = daemonRunning,
                onClick = onOpenTasks,
            )
            if (modeTarget != null) {
                ToolbarIcon(
                    icon = if (modeTarget == TerminalModeTarget.CONSOLE) Icons.Rounded.Terminal else Icons.Rounded.ViewAgenda,
                    contentDescription = stringResource(
                        if (modeTarget == TerminalModeTarget.CONSOLE) R.string.terminal_console_mode else R.string.terminal_block_mode,
                    ),
                    onClick = onSwitchMode,
                )
            }
        }
    }
}

@Composable
private fun EnvironmentSegments(
    options: List<TerminalEnvironment>,
    selected: TerminalEnvironment,
    onSelect: (TerminalEnvironment) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .squircleBackground(colors.surfaceContainer, 12.dp)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val background by animateColorAsState(
                targetValue = if (isSelected) colors.surface else Color.Transparent,
                label = "segmentBackground",
            )
            Box(
                modifier = Modifier
                    .squircleBackground(background, 9.dp)
                    .semantics {
                        role = Role.Tab
                        this.selected = isSelected
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(option) },
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.displayName,
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) colors.onSurface else colors.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun ToolbarIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    badge: String? = null,
    dot: Boolean = false,
) {
    IconButton(onClick = onClick, minWidth = 40.dp, minHeight = 40.dp) {
        Box {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
            when {
                badge != null -> Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-4).dp)
                        .squircleBackground(MiuixTheme.colorScheme.primary, 6.dp)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = badge,
                        style = MiuixTheme.textStyles.footnote2,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onPrimary,
                    )
                }
                dot -> Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = (-1).dp)
                        .size(6.dp)
                        .background(StatusSuccess, CircleShape),
                )
            }
        }
    }
}

/** 行内状态圆点：运行中为成功色，空闲为次要色，已退出为错误色。 */
@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(7.dp)
            .background(color, CircleShape),
    )
}

/** 列表行尾的紧凑图标操作。 */
@Composable
internal fun RowIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MiuixTheme.colorScheme.onSurfaceVariantActions,
) {
    IconButton(onClick = onClick, minWidth = 36.dp, minHeight = 36.dp) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(19.dp),
            tint = tint,
        )
    }
}

