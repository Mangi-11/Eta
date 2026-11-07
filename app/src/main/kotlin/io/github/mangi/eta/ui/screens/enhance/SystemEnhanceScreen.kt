package io.github.mangi.eta.ui.screens.enhance

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.app.description
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.model.AgentSystemEnhanceAction
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
fun SystemEnhanceScreen(
    onAction: (AgentSystemEnhanceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val capabilities = rememberDeviceCapabilities()
    val canRequestRoot = capabilities.root.suPresent && !capabilities.root.isGranted
    val rootButtonText = stringResource(
        if (canRequestRoot) R.string.capability_root_request else R.string.capability_root_refresh,
    )
    val onRootAction: () -> Unit = {
        onAction(
            if (canRequestRoot) AgentSystemEnhanceAction.RequestRoot else AgentSystemEnhanceAction.RefreshRoot,
        )
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.capability_enhancements),
                onBack = { onAction(AgentSystemEnhanceAction.NavigateBack) },
                modifier = modifier,
            ) {
                Group {
                    CouiPreference(
                        title = "Root",
                        summary = capabilities.root.description(context),
                        showChevron = false,
                        trailing = {
                            CouiButton(
                                text = rootButtonText,
                                enabled = !capabilities.root.isChecking,
                                minHeight = 32.dp,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                onClick = onRootAction,
                            )
                        },
                    )
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.capability_xposed_service),
                        summary = stringResource(
                            if (capabilities.xposedConnected) R.string.capability_xposed_connected
                            else R.string.capability_xposed_disconnected,
                        ),
                        showChevron = false,
                    )
                }

                Group {
                    CouiPreference(
                        title = stringResource(R.string.capability_xposed_help),
                        summary = stringResource(R.string.capability_xposed_help_summary),
                        showChevron = false,
                    )
                }

                Group(title = stringResource(R.string.capability_root_features)) {
                    CouiPreference(
                        title = stringResource(R.string.capability_root_device),
                        summary = stringResource(R.string.capability_root_device_summary),
                        showChevron = false,
                    )
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.capability_root_data),
                        summary = stringResource(R.string.capability_root_data_summary),
                        showChevron = false,
                    )
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.capability_root_linux),
                        summary = stringResource(R.string.capability_root_linux_summary),
                        showChevron = false,
                    )
                }

                Group(title = stringResource(R.string.capability_system_features)) {
                    CouiPreference(
                        title = stringResource(R.string.capability_hook_assistants),
                        summary = stringResource(R.string.capability_hook_assistants_summary),
                        showChevron = false,
                    )
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.capability_hook_google),
                        summary = stringResource(R.string.capability_hook_google_summary),
                        showChevron = false,
                    )
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.capability_hook_accessibility),
                        summary = stringResource(R.string.capability_hook_accessibility_summary),
                        showChevron = false,
                    )
                }

                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.capability_enhancements),
                onBack = { onAction(AgentSystemEnhanceAction.NavigateBack) },
                modifier = modifier,
            ) {
                item(key = "access") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(top = 8.dp, bottom = 12.dp),
                    ) {
                        BasicComponent(
                            title = "Root",
                            summary = capabilities.root.description(context),
                            endActions = {
                                TextButton(
                                    text = rootButtonText,
                                    enabled = !capabilities.root.isChecking,
                                    minWidth = 0.dp,
                                    minHeight = 32.dp,
                                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    onClick = onRootAction,
                                )
                            },
                        )
                        BasicComponent(
                            title = stringResource(R.string.capability_xposed_service),
                            summary = stringResource(
                                if (capabilities.xposedConnected) R.string.capability_xposed_connected
                                else R.string.capability_xposed_disconnected,
                            ),
                        )
                    }
                }
                item(key = "framework-help") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        BasicComponent(
                            title = stringResource(R.string.capability_xposed_help),
                            summary = stringResource(R.string.capability_xposed_help_summary),
                        )
                    }
                }
                item(key = "root-features") {
                    SmallTitle(stringResource(R.string.capability_root_features))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        BasicComponent(
                            title = stringResource(R.string.capability_root_device),
                            summary = stringResource(R.string.capability_root_device_summary),
                        )
                        BasicComponent(
                            title = stringResource(R.string.capability_root_data),
                            summary = stringResource(R.string.capability_root_data_summary),
                        )
                        BasicComponent(
                            title = stringResource(R.string.capability_root_linux),
                            summary = stringResource(R.string.capability_root_linux_summary),
                        )
                    }
                }
                item(key = "hook-features") {
                    SmallTitle(stringResource(R.string.capability_system_features))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        BasicComponent(
                            title = stringResource(R.string.capability_hook_assistants),
                            summary = stringResource(R.string.capability_hook_assistants_summary),
                        )
                        BasicComponent(
                            title = stringResource(R.string.capability_hook_google),
                            summary = stringResource(R.string.capability_hook_google_summary),
                        )
                        BasicComponent(
                            title = stringResource(R.string.capability_hook_accessibility),
                            summary = stringResource(R.string.capability_hook_accessibility_summary),
                        )
                    }
                }
            }
        }
    }
}
