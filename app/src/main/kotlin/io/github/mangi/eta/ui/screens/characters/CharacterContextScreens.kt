package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
internal fun CharacterPersonaScreen(store: CharacterLibraryStore, onBack: () -> Unit) {
    MiuixScaffoldPage(title = "我的人设", onBack = onBack, modifier = Modifier.imePadding()) {
        item { CharacterPageMessage("设置你在故事中的身份。开始对话时可以选择是否使用；已开始的故事保留当时的人设。") }
        item(key = "name") { CharacterTextField("称呼", store.personaDraft.name, { store.updatePersona(name = it) }, !store.busy, singleLine = true) }
        item(key = "persona") { CharacterTextField("身份与关系", store.personaDraft.description, { store.updatePersona(description = it) }, !store.busy, minLines = 6) }
        item(key = "save") {
            TextButton("保存人设", onClick = { store.savePersona(onBack) }, enabled = !store.busy,
                modifier = Modifier.fillMaxWidth().padding(16.dp), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
internal fun CharacterMemoryScreen(id: String, store: CharacterLibraryStore, onBack: () -> Unit) {
    MiuixScaffoldPage(title = "剧情记忆", onBack = onBack, modifier = Modifier.imePadding()) {
        item { CharacterPageMessage("记录这个角色的重要经历、关系与约定。它由此角色的各次对话共享，受记忆总开关控制。") }
        item(key = "memory") {
            CharacterTextField("剧情与关系", store.memoryDraft, store::updateMemory, !store.busy, minLines = 10)
        }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton("重新载入", onClick = { store.loadMemory(id, force = true) }, enabled = !store.busy, modifier = Modifier.weight(1f))
                TextButton("保存记忆", onClick = { store.saveMemory(id) }, enabled = !store.busy, modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}
