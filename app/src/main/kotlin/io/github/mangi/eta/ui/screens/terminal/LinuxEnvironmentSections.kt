package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiPreference
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LinuxEnvironmentStatusCard(
    title: String,
    mode: String,
    summary: String,
    busy: Boolean,
    message: String?,
    actionText: String?,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    val uiStyle = LocalUiStyle.current
    val body: @Composable () -> Unit = {
        Column(
            modifier = if (uiStyle == AppearanceUiStyle.COUI) {
                Modifier.padding(horizontal = CouiPageDefaults.rowPadding, vertical = 16.dp)
            } else {
                Modifier
            },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, style = MiuixTheme.textStyles.headline1)
                Text(
                    text = mode,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) InfiniteProgressIndicator(size = 20.dp)
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
            }
            message?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            actionText?.let {
                if (uiStyle == AppearanceUiStyle.COUI) {
                    CouiButton(
                        text = it,
                        enabled = actionEnabled,
                        role = CouiButtonRole.Primary,
                        onClick = onAction,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    TextButton(
                        text = it,
                        enabled = actionEnabled,
                        onClick = onAction,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }

    when (uiStyle) {
        AppearanceUiStyle.COUI -> {
            Group {
                body()
            }
        }
        AppearanceUiStyle.MIUIX -> {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp, bottom = 12.dp),
                insideMargin = PaddingValues(16.dp),
            ) {
                body()
            }
        }
    }
}

@Composable
internal fun LinuxEnvironmentConfiguration(
    distribution: LinuxDistribution,
    backend: LinuxExecutionBackend,
    rootGranted: Boolean,
    enabled: Boolean,
    onDistributionSelected: (LinuxDistribution) -> Unit,
    onBackendSelected: (LinuxExecutionBackend) -> Unit,
) {
    val distributions = LinuxDistribution.entries
    val backends = listOf(LinuxExecutionBackend.PROOT, LinuxExecutionBackend.CHROOT)
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            Group(title = stringResource(R.string.linux_environment_configuration)) {
                CouiDropdownPreference(
                    title = stringResource(R.string.linux_distribution_title),
                    items = distributions.map { it.displayName() },
                    selectedIndex = distributions.indexOf(distribution),
                    enabled = enabled,
                    onSelectedIndexChange = { onDistributionSelected(distributions[it]) },
                )
                Divider()
                if (rootGranted || backend == LinuxExecutionBackend.CHROOT) {
                    CouiDropdownPreference(
                        title = stringResource(R.string.capability_linux_backend),
                        items = backends.map { it.displayName() },
                        selectedIndex = backends.indexOf(backend),
                        enabled = enabled,
                        onSelectedIndexChange = { index ->
                            val chosen = backends.getOrNull(index) ?: return@CouiDropdownPreference
                            if (chosen == LinuxExecutionBackend.PROOT || rootGranted) {
                                onBackendSelected(chosen)
                            }
                        },
                    )
                } else {
                    CouiPreference(
                        title = stringResource(R.string.capability_linux_backend),
                        value = backend.displayName(),
                        showChevron = false,
                    )
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                WindowSpinnerPreference(
                    title = stringResource(R.string.linux_distribution_title),
                    items = distributions.map {
                        DropdownItem(
                            text = it.displayName(),
                            summary = stringResource(
                                when (it) {
                                    LinuxDistribution.ALPINE -> R.string.linux_distribution_alpine_summary
                                    LinuxDistribution.DEBIAN -> R.string.linux_distribution_debian_summary
                                },
                            ),
                        )
                    },
                    selectedIndex = distributions.indexOf(distribution),
                    enabled = enabled,
                    onSelectedIndexChange = { onDistributionSelected(distributions[it]) },
                )
                if (rootGranted || backend == LinuxExecutionBackend.CHROOT) {
                    WindowSpinnerPreference(
                        title = stringResource(R.string.capability_linux_backend),
                        items = backends.map {
                            DropdownItem(
                                text = it.displayName(),
                                summary = stringResource(
                                    when (it) {
                                        LinuxExecutionBackend.PROOT -> R.string.capability_linux_proot_summary
                                        LinuxExecutionBackend.CHROOT -> R.string.capability_linux_chroot_summary
                                    },
                                ),
                                enabled = it == LinuxExecutionBackend.PROOT || rootGranted,
                            )
                        },
                        selectedIndex = backends.indexOf(backend),
                        enabled = enabled,
                        onSelectedIndexChange = { onBackendSelected(backends[it]) },
                    )
                } else {
                    BasicComponent(
                        title = stringResource(R.string.capability_linux_backend),
                        endActions = {
                            Text(
                                text = backend.displayName(),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun LinuxDistribution.displayName(): String = stringResource(
    when (this) {
        LinuxDistribution.ALPINE -> R.string.linux_distribution_alpine
        LinuxDistribution.DEBIAN -> R.string.linux_distribution_debian
    },
)

@Composable
internal fun LinuxExecutionBackend.displayName(): String = stringResource(
    when (this) {
        LinuxExecutionBackend.PROOT -> R.string.capability_linux_proot
        LinuxExecutionBackend.CHROOT -> R.string.capability_linux_chroot
    },
)
