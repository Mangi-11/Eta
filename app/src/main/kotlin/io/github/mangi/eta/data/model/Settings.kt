package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val defaultAssistantSystemPrompt: String = "",
    val memoryEnabled: Boolean = true,
    val autoMemoryEnabled: Boolean = true,
    val autoSkillsEnabled: Boolean = true,
    val virtualScreenEnabled: Boolean = false,
    val virtualScreenOffEnabled: Boolean = false,
    val virtualScreenFallbackEnabled: Boolean = false,
    val virtualScreenAutoRestartApps: Boolean = false,
    val virtualScreenIdleTimeoutMinutes: Int = 20,
    val appearance: AppearanceSettings = AppearanceSettings(),
)

internal object VirtualScreenIdleTimeout {
    val options = listOf(10, 20, 60, 0)
    fun normalize(minutes: Int): Int = minutes.takeIf { it in options } ?: 20
}
