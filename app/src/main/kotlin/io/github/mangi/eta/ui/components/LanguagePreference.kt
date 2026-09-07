package io.github.mangi.eta.ui.components

import android.os.LocaleList
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.LanguageSettingsRepository
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

@Composable
internal fun LanguagePreference() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val repository = remember(context.applicationContext) {
        LanguageSettingsRepository(context.applicationContext)
    }
    var selectedLocale by remember(repository, configuration) {
        mutableStateOf(repository.selectedLocale())
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, repository) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                selectedLocale = repository.selectedLocale()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val locales = repository.supportedLocales
    val labels = listOf(stringResource(R.string.settings_language_system)) +
        locales.map { it.getDisplayName(it) }
    val selectedIndex = selectedLocale?.let { selected ->
        locales.indexOfFirst { LocaleList.matchesLanguageAndScript(it, selected) } + 1
    } ?: 0

    OverlayDropdownPreference(
        title = stringResource(R.string.settings_language),
        items = labels,
        selectedIndex = selectedIndex,
        startAction = { PreferenceIcon(icon = Icons.Rounded.Language) },
        onSelectedIndexChange = { index ->
            if (index in labels.indices) {
                repository.selectLocale(if (index == 0) null else locales[index - 1])
                selectedLocale = repository.selectedLocale()
            }
        },
    )
}
