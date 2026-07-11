package fuck.andes.agent.runtime

import android.content.SharedPreferences
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.data.model.CustomBody
import java.lang.reflect.Proxy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimePolicyTest {
    @Test
    fun unavailablePreferencesFailClosed() {
        assertEquals(
            AgentRuntimePolicy.Permissions(
                terminalTools = false,
                browserTools = false,
                thinking = false,
            ),
            AgentRuntimePolicy.permissions(null),
        )
    }

    @Test
    fun missingKeysUseConfiguredCapabilityDefaults() {
        val preferences = booleanPreferences { _, default -> default }

        assertEquals(
            AgentRuntimePolicy.Permissions(
                terminalTools = false,
                browserTools = true,
                thinking = false,
            ),
            AgentRuntimePolicy.permissions(preferences),
        )
    }

    @Test
    fun preferenceReadFailuresFailClosedForEveryCapability() {
        val preferences = booleanPreferences { _, _ -> error("remote preferences unavailable") }

        assertEquals(
            AgentRuntimePolicy.Permissions(
                terminalTools = false,
                browserTools = false,
                thinking = false,
            ),
            AgentRuntimePolicy.permissions(preferences),
        )
    }

    @Test
    fun callerCannotEnableCapabilitiesThatRuntimeDidNotAuthorize() {
        val constrained = AgentRuntimePolicy.constrain(
            config = modelConfig(
                terminalTools = true,
                browserTools = true,
                thinking = true,
            ),
            permissions = AgentRuntimePolicy.Permissions(
                terminalTools = false,
                browserTools = false,
                thinking = false,
            ),
        )

        assertFalse(constrained.terminalTools)
        assertFalse(constrained.browserTools)
        assertFalse(constrained.thinkingEnabled)
    }

    @Test
    fun runtimePermissionDoesNotOverrideCallerFeatureSelection() {
        val constrained = AgentRuntimePolicy.constrain(
            config = modelConfig(
                terminalTools = false,
                browserTools = true,
                thinking = false,
            ),
            permissions = AgentRuntimePolicy.Permissions(
                terminalTools = true,
                browserTools = true,
                thinking = true,
            ),
        )

        assertFalse(constrained.terminalTools)
        assertTrue(constrained.browserTools)
        assertFalse(constrained.thinkingEnabled)
    }

    @Test
    fun disabledThinkingCannotBeReenabledThroughCustomRequestBody() {
        val constrained = AgentRuntimePolicy.constrain(
            config = modelConfig(
                terminalTools = false,
                browserTools = false,
                thinking = true,
            ).copy(
                extraBodyJson = """{"thinking":{"budget_tokens":4096},"temperature":0.2}""",
                customBody = listOf(
                    CustomBody("reasoning_effort", JsonPrimitive("high")),
                    CustomBody(
                        "metadata",
                        JsonObject(
                            mapOf(
                                "enable_thinking" to JsonPrimitive(true),
                                "safe" to JsonPrimitive("kept"),
                            )
                        ),
                    ),
                ),
            ),
            permissions = AgentRuntimePolicy.Permissions(
                terminalTools = false,
                browserTools = false,
                thinking = false,
            ),
        )

        assertFalse(constrained.thinkingEnabled)
        val extra = JSONObject(constrained.extraBodyJson)
        assertFalse(extra.has("thinking"))
        assertEquals(0.2, extra.getDouble("temperature"), 0.0)
        assertEquals(listOf("metadata"), constrained.customBody.map { it.key })
        val metadata = constrained.customBody.single().value as JsonObject
        assertFalse("enable_thinking" in metadata)
        assertEquals(JsonPrimitive("kept"), metadata["safe"])
    }

    private fun modelConfig(
        terminalTools: Boolean,
        browserTools: Boolean,
        thinking: Boolean,
    ): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
            terminalTools = terminalTools,
            browserTools = browserTools,
            thinkingEnabled = thinking,
        )

    private fun booleanPreferences(
        getBoolean: (key: String, default: Boolean) -> Boolean,
    ): SharedPreferences =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getBoolean" -> getBoolean(
                    arguments?.get(0) as String,
                    arguments[1] as Boolean,
                )
                "equals" -> proxy === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "BooleanSharedPreferences"
                else -> error("Unexpected SharedPreferences method: ${method.name}")
            }
        } as SharedPreferences
}
