package io.github.mangi.eta.ui.screens.tasks

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.display.MainScreenFallbackApproval
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.agent.display.VirtualScreenViewerActivity
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.ui.app.rememberExecutionNotificationRequest
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
internal fun VirtualScreenSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by SettingsDataStore.settingsFlow().collectAsState(initial = Settings())
    val scope = rememberCoroutineScope()
    val requestNotifications = rememberExecutionNotificationRequest()
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
