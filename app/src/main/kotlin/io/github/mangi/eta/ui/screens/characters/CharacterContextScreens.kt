package io.github.mangi.eta.ui.screens.characters
import io.github.mangi.eta.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.ButtonDefaults

@Composable
internal fun CharacterPersonaScreen(store: CharacterLibraryStore, onBack: () -> Unit) {
    MiuixScaffoldPage(title = stringResource(R.string.character_user_persona), onBack = onBack, modifier = Modifier.imePadding()) {
        item { CharacterPageMessage(stringResource(R.string.character_persona_desc)) }
        item(key = "name") { CharacterTextField(stringResource(R.string.character_addressing_name), store.personaDraft.name, { store.updatePersona(name = it) }, !store.busy, singleLine = true) }
        item(key = "persona") { CharacterTextField(stringResource(R.string.character_identity_and_relationship), store.personaDraft.description, { store.updatePersona(description = it) }, !store.busy, minLines = 6) }
        item(key = "save") {
            EtaTextButton(stringResource(R.string.character_save_persona), onClick = { store.savePersona(onBack) }, enabled = !store.busy,
                modifier = Modifier.fillMaxWidth().padding(16.dp), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
internal fun CharacterMemoryScreen(id: String, store: CharacterLibraryStore, onBack: () -> Unit) {
    MiuixScaffoldPage(title = stringResource(R.string.character_plot_memory), onBack = onBack, modifier = Modifier.imePadding()) {
        item { CharacterPageMessage(stringResource(R.string.character_memory_desc)) }
        item(key = "memory") {
            CharacterTextField(stringResource(R.string.character_scenario_and_relationship), store.memoryDraft, store::updateMemory, !store.busy, minLines = 10)
        }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EtaTextButton(stringResource(R.string.catalog_reload), onClick = { store.loadMemory(id, force = true) }, enabled = !store.busy, modifier = Modifier.weight(1f))
                EtaTextButton(stringResource(R.string.character_save_memory), onClick = { store.saveMemory(id) }, enabled = !store.busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}
