package io.github.mangi.eta.ui.navigation

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRouteSerializationTest {
    @Test
    fun legacyBrowserRouteRestoresTheManualPage() {
        val restored = Json.decodeFromString<AppRoute>(
            """{"type":"io.github.mangi.eta.ui.navigation.AppRoute.Browser"}""",
        )
        assertEquals(AppRoute.Browser(), restored)
    }

    @Test
    fun allRoutesRoundTripWithMiuixNavJsonConfiguration() {
        val routes = listOf<AppRoute>(
            AppRoute.Home,
            AppRoute.Browser(),
            AppRoute.Browser("conversation-a"),
            AppRoute.Browser("conversation-b"),
            AppRoute.Terminal,
            AppRoute.Tools,
            AppRoute.WebToolsSettings,
            AppRoute.Skills,
            AppRoute.Subagents,
            AppRoute.ConversationTitleSettings,
            AppRoute.AssistantOverlaySettings,
            AppRoute.Characters,
            AppRoute.CharacterDetail("character"),
            AppRoute.CharacterEditor(),
            AppRoute.CharacterEditor("character"),
            AppRoute.CharacterPersona,
            AppRoute.CharacterMemory("character"),
            AppRoute.Permissions,
            AppRoute.SystemEnhance,
            AppRoute.Settings,
            AppRoute.AppearanceSettings,
            AppRoute.SpeechSettings,
            AppRoute.SpeechRecognition,
            AppRoute.SpeechSynthesis,
            AppRoute.SpeechOss,
            AppRoute.DataBackup,
            AppRoute.Memory,
            AppRoute.LinuxEnvironment,
            AppRoute.SharedFolders,
            AppRoute.Workspace,
            AppRoute.LinuxFiles("alpine"),
            AppRoute.ModelProviders,
            AppRoute.CommunityCatalog,
            AppRoute.CommunityCatalogProvider("provider"),
            AppRoute.McpServers,
            AppRoute.McpServerDetail("mcp-server"),
            AppRoute.ModelProviderDetail("provider"),
            AppRoute.ModelProviderNew(NewProviderType.OpenAiCompatible),
            AppRoute.ModelProviderNew(NewProviderType.Anthropic),
            AppRoute.ModelProviderNew(NewProviderType.Gemini),
        )
        val json = Json { ignoreUnknownKeys = true }

        val encoded = json.encodeToString(routes)

        assertEquals(routes, json.decodeFromString<List<AppRoute>>(encoded))
    }
}
