package io.github.mangi.eta.agent.runtime

import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.ReasoningEffort
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
                autoCompaction = false,
                subagentTools = false,
                virtualDisplay = false,
            ),
            AgentRuntimePolicy.permissions(null),
        )
    }

    @Test
    fun missingKeysUseConfiguredCapabilityDefaults() {
        val preferences = booleanPreferences { _, default -> default }

        assertEquals(
            AgentRuntimePolicy.Permissions(
                terminalTools = true,
                browserTools = true,
                deviceDirectTools = true,
                deviceSensitiveReadTools = true,
                deviceSensitiveActionTools = true,
                subagentTools = true,
                virtualDisplay = true,
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
                autoCompaction = false,
                subagentTools = false,
                virtualDisplay = false,
            ),
            AgentRuntimePolicy.permissions(preferences),
        )
    }

    @Test
    fun automaticCompactionRequiresBothLocalSettingAndRequestSelection() {
        for (localEnabled in listOf(false, true)) {
            val preferences = booleanPreferences { key, default ->
                if (key == Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED) localEnabled else default
            }
            val permissions = AgentRuntimePolicy.permissions(preferences)
            for (requested in listOf(false, true)) {
                val config = modelConfig(false, false, false).copy(autoCompactionEnabled = requested)
                assertEquals(localEnabled && requested, AgentRuntimePolicy.constrain(config, permissions).autoCompactionEnabled)
            }
        }
    }

    @Test
    fun callerCannotEnableCapabilitiesThatRuntimeDidNotAuthorize() {
        val constrained = AgentRuntimePolicy.constrain(
            config = modelConfig(
                terminalTools = true,
                browserTools = true,
                thinking = true,
            ).copy(
                deviceDirectTools = true,
                deviceSensitiveReadTools = true,
                deviceSensitiveActionTools = true,
                subagentTools = true,
            ),
            permissions = AgentRuntimePolicy.Permissions(
                terminalTools = false,
                browserTools = false,
                subagentTools = false,
            ),
        )

        assertFalse(constrained.terminalTools)
        assertFalse(constrained.browserTools)
        assertTrue(constrained.thinkingEnabled)
        assertEquals(ReasoningEffort.DEFAULT, constrained.reasoningEffort)
        assertFalse(constrained.deviceDirectTools)
        assertFalse(constrained.deviceSensitiveReadTools)
        assertFalse(constrained.deviceSensitiveActionTools)
        assertFalse(constrained.subagentTools)
    }

    @Test
    fun explicitReasoningInRequestIsNotSuppressedWhenDefaultThinkingPreferenceIsOff() {
        val preferences = booleanPreferences { key, default ->
            if (key == Prefs.Keys.AGENT_THINKING_ENABLED) false else default
        }
        val permissions = AgentRuntimePolicy.permissions(preferences)
        val constrained = AgentRuntimePolicy.constrain(
            config = modelConfig(
                terminalTools = false,
                browserTools = false,
                thinking = true,
            ).copy(reasoningEffort = ReasoningEffort.HIGH),
            permissions = permissions,
        )

        assertTrue(constrained.thinkingEnabled)
        assertEquals(ReasoningEffort.HIGH, constrained.reasoningEffort)
    }

    @Test
    fun runtimePermissionDoesNotOverrideCallerFeatureSelection() {
        val constrained = AgentRuntimePolicy.constrain(
            config = modelConfig(
                terminalTools = false,
                browserTools = true,
                thinking = false,
            ).copy(subagentTools = false),
            permissions = AgentRuntimePolicy.Permissions(
                terminalTools = true,
                browserTools = true,
                subagentTools = true,
            ),
        )

        assertFalse(constrained.terminalTools)
        assertTrue(constrained.browserTools)
        assertFalse(constrained.thinkingEnabled)
        assertFalse(constrained.subagentTools)
    }

    @Test
    fun disabledThinkingCannotBeReenabledThroughCustomRequestBody() {
        val constrained = AgentRuntimePolicy.constrain(
            config = modelConfig(
                terminalTools = false,
                browserTools = false,
                thinking = false,
            ).copy(
                extraBodyJson = """{"thinking":{"budget_tokens":4096},"temperature":0.2,"generation_config":{"thinking_level":"high","thinking_summaries":"auto","max_output_tokens":4096}}""",
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
            ),
        )

        assertFalse(constrained.thinkingEnabled)
        val extra = JSONObject(constrained.extraBodyJson)
        assertFalse(extra.has("thinking"))
        assertEquals(0.2, extra.getDouble("temperature"), 0.0)
        val generation = extra.getJSONObject("generation_config")
        assertFalse(generation.has("thinking_level"))
        assertFalse(generation.has("thinking_summaries"))
        assertEquals(4096, generation.getInt("max_output_tokens"))
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
