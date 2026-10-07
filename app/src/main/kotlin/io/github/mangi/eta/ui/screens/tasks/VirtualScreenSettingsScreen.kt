package io.github.mangi.eta.ui.screens.tasks

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.display.MainScreenFallbackApproval
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.agent.display.VirtualScreenViewerActivity
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.data.model.VirtualScreenIdleTimeout
import io.github.mangi.eta.ui.app.rememberExecutionNotificationRequest
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
internal fun VirtualScreenSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by SettingsDataStore.settingsFlow().collectAsState(initial = Settings())
    val scope = rememberCoroutineScope()
    val requestNotifications = rememberExecutionNotificationRequest()
    var timeoutDraft by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(settings.virtualScreenIdleTimeoutMinutes) {
        timeoutDraft = VirtualScreenIdleTimeout.options.indexOf(settings.virtualScreenIdleTimeoutMinutes).coerceAtLeast(0).toFloat()
    }
    MiuixScaffoldPage(title = stringResource(R.string.virtual_screen_title), onBack = onBack) {
        item {
            EtaPreferenceGroup {
                EtaSwitchPreference(
                    title = stringResource(R.string.virtual_screen_enable),
                    summary = stringResource(R.string.virtual_screen_enable_summary),
                    checked = settings.virtualScreenEnabled,
                    onCheckedChange = { value ->
                        scope.launch(Dispatchers.IO) {
                            SettingsDataStore.updateSettings {
                                it.copy(
                                    virtualScreenEnabled = value,
                                    virtualScreenOffEnabled = value && it.virtualScreenOffEnabled,
                                    virtualScreenFallbackEnabled = value && it.virtualScreenFallbackEnabled
                                )
                            }
                            if (!value) {
                                VirtualScreenSession.revokePermission()
                                MainScreenFallbackApproval.cancelAll()
                            }
                        }
                    })
                EtaPreferenceDivider(hasLeading = false)
                EtaSwitchPreference(
                    title = stringResource(R.string.virtual_screen_restart_enable),
                    summary = stringResource(R.string.virtual_screen_restart_summary),
                    checked = settings.virtualScreenAutoRestartApps,
                    enabled = settings.virtualScreenEnabled,
                    onCheckedChange = { value ->
                        scope.launch(Dispatchers.IO) {
                            SettingsDataStore.updateSettings { it.copy(virtualScreenAutoRestartApps = value) }
                        }
                    })
                EtaPreferenceDivider(hasLeading = false)
                EtaSwitchPreference(
                    title = stringResource(R.string.virtual_screen_fallback_enable),
                    summary = stringResource(R.string.virtual_screen_fallback_summary),
                    checked = settings.virtualScreenFallbackEnabled,
                    enabled = settings.virtualScreenEnabled,
                    onCheckedChange = { value ->
                        if (value) requestNotifications()
                        scope.launch(Dispatchers.IO) {
                            SettingsDataStore.updateSettings { it.copy(virtualScreenFallbackEnabled = value) }
                            if (!value) MainScreenFallbackApproval.cancelAll()
                        }
                    })
                EtaPreferenceDivider(hasLeading = false)
                EtaSwitchPreference(
                    title = stringResource(R.string.virtual_screen_off_enable),
                    summary = stringResource(R.string.virtual_screen_off_summary),
                    checked = settings.virtualScreenOffEnabled,
                    enabled = settings.virtualScreenEnabled,
                    onCheckedChange = { value ->
                        scope.launch(Dispatchers.IO) {
                            SettingsDataStore.updateSettings { it.copy(virtualScreenOffEnabled = value) }
                            if (!value) VirtualScreenSession.revokePermission()
                        }
                    })
                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(
                    title = stringResource(R.string.virtual_screen_idle_timeout),
                    summary = stringResource(R.string.virtual_screen_idle_warning),
                    endActions = {
                        val minutes = VirtualScreenIdleTimeout.options[timeoutDraft.roundToInt().coerceIn(0, 3)]
                        Text(stringResource(if (minutes == 0) R.string.virtual_screen_idle_never else R.string.virtual_screen_idle_minutes, minutes),
                            style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                    },
                    bottomAction = {
                        Column {
                            Slider(value = timeoutDraft, onValueChange = { timeoutDraft = it },
                                valueRange = 0f..3f, modifier = Modifier.fillMaxWidth(),
                                showKeyPoints = true, keyPoints = listOf(0f, 1f, 2f, 3f),
                                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                                onValueChangeFinished = {
                                    timeoutDraft = timeoutDraft.roundToInt().coerceIn(0, 3).toFloat()
                                    val minutes = VirtualScreenIdleTimeout.options[timeoutDraft.toInt()]
                                    scope.launch(Dispatchers.IO) {
                                        SettingsDataStore.updateSettings { it.copy(virtualScreenIdleTimeoutMinutes = minutes) }
                                        VirtualScreenSession.updateIdleTimeout()
                                    }
                                })
                            Row(Modifier.fillMaxWidth()) {
                                VirtualScreenIdleTimeout.options.forEach { minutes ->
                                    Text(stringResource(if (minutes == 0) R.string.virtual_screen_idle_never else R.string.virtual_screen_idle_minutes, minutes),
                                        modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                                        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                            }
                        }
                    },
                    onClick = {},
                )
            }
        }
        item {
            EtaTextButton(
                text = stringResource(R.string.virtual_screen_view),
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = EtaPreferenceDefaults.SidePadding),
                onClick = {
                    context.startActivity(
                        Intent(
                            context,
                            VirtualScreenViewerActivity::class.java
                        )
                    )
                })
        }
    }
}
