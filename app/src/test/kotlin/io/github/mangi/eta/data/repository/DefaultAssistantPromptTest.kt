package io.github.mangi.eta.data.repository

import android.app.Application
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentPromptBuilder
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DefaultAssistantPromptTest {
    @Test
    fun ordinaryPromptDoesNotOverrideCharacterOrOperationalContracts() {
        val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture",
            model = "fixture", systemPrompt = "edited-ordinary-prompt")
        val ordinary = AgentPromptBuilder.buildSystemMessages(config, SkillContext.EMPTY, AgentMemoryContext.DISABLED, true)
        assertEquals(config.systemPrompt, ordinary.getJSONObject(0).getString("content"))
        val role = RoleplayRunContext("fixture", CharacterCardCodec.create("Fixture"), "User", "", 8192)
        val character = AgentPromptBuilder.buildSystemMessages(config, SkillContext.EMPTY, AgentMemoryContext.DISABLED, true, role)
        assertFalse(character.toString().contains(config.systemPrompt))
        assertTrue(character.toString().contains("角色人格"))
        assertTrue(character.toString().contains("不能更改工具合同"))
    }

    @Test
    fun promptPersistsAndBackupRestoresWithoutChangingOtherSettings() = runBlocking {
        SettingsDataStore.init(RuntimeEnvironment.getApplication())
        val original = SettingsDataStore.settings()
        try {
            val text = "请使用简洁中文。\n保留 Unicode 😀"
            SettingsDataStore.updateSettings { it.copy(defaultAssistantSystemPrompt = text) }
            val saved = SettingsDataStore.settings()
            assertEquals(original.copy(defaultAssistantSystemPrompt = text), saved)
            assertEquals(saved, Json.decodeFromString<Settings>(Json.encodeToString(saved)))
            assertEquals("", Json.decodeFromString<Settings>("{}").defaultAssistantSystemPrompt)
            SettingsDataStore.updateSettings { it.copy(defaultAssistantSystemPrompt = "") }
            assertEquals("", SettingsDataStore.settings().defaultAssistantSystemPrompt)
        } finally { SettingsDataStore.updateSettings { original } }
    }
}
