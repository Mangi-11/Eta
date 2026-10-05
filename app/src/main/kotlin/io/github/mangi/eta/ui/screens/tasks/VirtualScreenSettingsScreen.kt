package io.github.mangi.eta.ui.screens.tasks

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.display.VirtualScreenViewerActivity
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffold
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

@Composable
internal fun VirtualScreenSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by SettingsDataStore.settingsFlow().collectAsState(initial = Settings())
    val scope = rememberCoroutineScope()
    var scene by remember { mutableStateOf("") }
    LaunchedEffect(settings.vivoAtomicScene) { scene = settings.vivoAtomicScene }
    MiuixScaffold(
        title = stringResource(R.string.virtual_screen_title),
        onBack = onBack
    ) { padding, _, _ ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
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
                                    virtualScreenOffEnabled = if (value) it.virtualScreenOffEnabled else false
                                )
                            }; if (!value) io.github.mangi.eta.agent.display.VirtualScreenSession.revokePermission()
                        }
                    })
                EtaSwitchPreference(
                    title = stringResource(R.string.virtual_screen_off_enable),
                    summary = stringResource(R.string.virtual_screen_off_summary),
                    checked = settings.virtualScreenOffEnabled,
                    enabled = settings.virtualScreenEnabled,
                    onCheckedChange = { value ->
                        scope.launch(Dispatchers.IO) {
                            SettingsDataStore.updateSettings {
                                it.copy(
                                    virtualScreenOffEnabled = value
                                )
                            }; if (!value) io.github.mangi.eta.agent.display.VirtualScreenSession.revokePermission()
                        }
                    })
            }
            EtaTextButton(
                text = stringResource(R.string.virtual_screen_view),
                onClick = {
                    context.startActivity(
                        Intent(
                            context,
                            VirtualScreenViewerActivity::class.java
                        )
                    )
                })
            if (android.os.Build.MANUFACTURER.equals("vivo", ignoreCase = true)) {
                io.github.mangi.eta.ui.components.EtaPreference(
                    title = stringResource(R.string.vivo_atomic_scene_title),
                    summary = stringResource(R.string.vivo_atomic_scene_summary)
                )
                top.yukonga.miuix.kmp.basic.TextField(
                    value = scene,
                    onValueChange = { scene = it.take(64) },
                    label = stringResource(R.string.vivo_atomic_scene_title)
                )
                EtaTextButton(
                    text = stringResource(R.string.vivo_atomic_scene_save),
                    enabled = scene.isBlank() || Regex("[A-Z][A-Z0-9_]{0,63}").matches(scene),
                    onClick = {
                        scope.launch {
                            SettingsDataStore.updateSettings {
                                it.copy(
                                    vivoAtomicScene = scene
                                )
                            }
                        }
                    })
            }
        }
    }
}
