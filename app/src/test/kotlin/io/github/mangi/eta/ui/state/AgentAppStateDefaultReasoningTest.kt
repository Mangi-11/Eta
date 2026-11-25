package io.github.mangi.eta.ui.state

import android.content.Context
import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.runtime.ipc.AgentRuntimeWire
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.UserMessageUi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentAppStateDefaultReasoningTest {
    private lateinit var context: Context
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(RuntimeEnvironment.getApplication()).declareActionUnbindable(AgentRuntimeWire.ACTION_BIND)
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        Prefs.initLocal(context)
        Prefs.localAgentPreferences()?.edit()?.clear()?.commit()
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        seedProviders()
    }

    @After
    fun tearDown() {
        Prefs.localAgentPreferences()?.edit()?.clear()?.commit()
        scope.cancel()
        awaitCondition { job.isCompleted }
        EtaDatabase.closeForTests()
    }

    @Test
    fun unboundDraftFollowsDefaultReasoningSettingsAndNormalizesAcrossModels() {
        val prefs = checkNotNull(Prefs.localAgentPreferences())
        prefs.edit()
            .putBoolean(Prefs.Keys.AGENT_THINKING_ENABLED, true)
            .putString(Prefs.Keys.AGENT_DEFAULT_REASONING_EFFORT, ReasoningEffort.HIGH.wireValue)
            .commit()

        val state = AgentAppState(context, scope)
        awaitCondition {
            state.modelPickerState.selectedModel?.id == "model-full" &&
                state.homeState.reasoningEffort == ReasoningEffort.HIGH
        }
        assertTrue(state.homeState.thinkingEnabled)

        state.selectModel("model-limited")
        awaitCondition {
            state.homeState.reasoningEffort == ReasoningEffort.MEDIUM &&
                !state.modelPickerState.isChanging
        }
        assertTrue(state.homeState.thinkingEnabled)

        state.selectModel("model-full")
        awaitCondition {
            state.homeState.reasoningEffort == ReasoningEffort.HIGH &&
                !state.modelPickerState.isChanging
        }

        prefs.edit().putBoolean(Prefs.Keys.AGENT_THINKING_ENABLED, false).commit()
        awaitCondition { state.homeState.reasoningEffort == ReasoningEffort.OFF }
        assertFalse(state.homeState.thinkingEnabled)
    }

    @Test
    fun manualReasoningOverrideIsPreservedWhenDefaultThinkingIsOff() {
        val prefs = checkNotNull(Prefs.localAgentPreferences())
        prefs.edit()
            .putBoolean(Prefs.Keys.AGENT_THINKING_ENABLED, false)
            .putString(Prefs.Keys.AGENT_DEFAULT_REASONING_EFFORT, ReasoningEffort.LOW.wireValue)
            .commit()

        val state = AgentAppState(context, scope)
        awaitCondition {
            state.modelPickerState.selectedModel?.id == "model-full" &&
                state.homeState.reasoningEffort == ReasoningEffort.OFF
        }

        state.updateReasoningEffort(ReasoningEffort.HIGH)
        assertEquals(ReasoningEffort.HIGH, state.homeState.reasoningEffort)
        assertTrue(state.homeState.thinkingEnabled)

        prefs.edit().putString(Prefs.Keys.AGENT_DEFAULT_REASONING_EFFORT, ReasoningEffort.MAX.wireValue).commit()
        ShadowLooper.idleMainLooper()
        assertEquals(ReasoningEffort.HIGH, state.homeState.reasoningEffort)

        state.createConversation()
        assertEquals(ReasoningEffort.OFF, state.homeState.reasoningEffort)
        assertFalse(state.homeState.thinkingEnabled)

        prefs.edit().putBoolean(Prefs.Keys.AGENT_THINKING_ENABLED, true).commit()
        awaitCondition { state.homeState.reasoningEffort == ReasoningEffort.MAX }
        assertTrue(state.homeState.thinkingEnabled)
    }

    @Test
    fun loadedConversationReasoningIsNotOverwrittenByDefaultSettingChanges() {
        runBlocking {
            val message = UserMessageUi("u1", "你好")
            val history = listOf(AgentModelClient.ConversationMessage(role = "user", content = "你好", messageId = "u1"))
            val existing = AgentChatHomeUiState(
                messages = listOf(message),
                history = history,
                journal = history,
                input = "",
                isStreaming = false,
                thinkingEnabled = true,
                reasoningEffort = ReasoningEffort.LOW,
                modelId = "model-full",
            )
            AgentConversationStore.save(
                context = context,
                selectedConversationId = "conv-existing",
                conversationsById = mapOf("conv-existing" to existing),
                titles = mapOf("conv-existing" to "已有对话"),
                updatedAt = mapOf("conv-existing" to 1L),
            )
        }

        val prefs = checkNotNull(Prefs.localAgentPreferences())
        prefs.edit()
            .putBoolean(Prefs.Keys.AGENT_THINKING_ENABLED, false)
            .putString(Prefs.Keys.AGENT_DEFAULT_REASONING_EFFORT, ReasoningEffort.MAX.wireValue)
            .commit()

        val state = AgentAppState(context, scope)
        awaitCondition { state.homeState.isConversationLoaded && state.modelPickerState.selectedModel != null }
        assertEquals(ReasoningEffort.LOW, state.homeState.reasoningEffort)
        assertTrue(state.homeState.thinkingEnabled)

        prefs.edit().putBoolean(Prefs.Keys.AGENT_THINKING_ENABLED, true).commit()
        ShadowLooper.idleMainLooper()
        assertEquals(ReasoningEffort.LOW, state.homeState.reasoningEffort)
    }

    private fun seedProviders() = runBlocking {
        val fullModel = Model(
            id = "model-full",
            modelId = "full-reasoning",
            displayName = "Full Reasoning",
            reasoningCapabilities = ModelReasoningCapabilities(
                supportedEfforts = listOf(
                    ReasoningEffort.LOW,
                    ReasoningEffort.MEDIUM,
                    ReasoningEffort.HIGH,
                    ReasoningEffort.MAX,
                ),
                defaultEffort = ReasoningEffort.MEDIUM,
                canDisable = true,
            ),
        )
        val limitedModel = Model(
            id = "model-limited",
            modelId = "limited-reasoning",
            displayName = "Limited Reasoning",
            reasoningCapabilities = ModelReasoningCapabilities(
                supportedEfforts = listOf(
                    ReasoningEffort.LOW,
                    ReasoningEffort.MEDIUM,
                ),
                defaultEffort = ReasoningEffort.LOW,
                canDisable = true,
            ),
        )
        ProviderRepository.addProvider(
            OpenAiCompatibleProviderSetting(
                id = "provider-test",
                name = "Test Provider",
                baseUrl = "https://example.invalid/v1",
                apiKey = "test-key",
                models = listOf(fullModel, limitedModel),
            ),
        )
        RuntimeConfigRepository.setSelectedModelId("model-full")
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
            Thread.sleep(1)
        }
        assertTrue("条件未在限定时间内满足", condition())
    }
}
