package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.hook.vivo.VivoHandoff
import io.github.mangi.eta.hook.vivo.VivoIslandNotifications
import io.github.mangi.eta.hook.vivo.VivoIslandWire
import io.github.mangi.eta.ui.MainActivity
import java.time.Duration
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimeIslandBridgeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val messages = mutableListOf<Message>()
    private val stopped = mutableListOf<String>()
    private lateinit var bridge: AgentRuntimeIslandBridge

    @Before
    fun setUp() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = ModuleConfig.VIVO_COPILOT_PACKAGE
            longVersionCode = 68503
            applicationInfo = ApplicationInfo().apply { packageName = ModuleConfig.VIVO_COPILOT_PACKAGE }
        })
        val server = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) { messages += Message.obtain(msg) }
        })
        shadowOf(context).setComponentNameAndServiceForBindService(
            VivoIslandWire.serviceIntent().component, server.binder,
        )
        bridge = AgentRuntimeIslandBridge(context, AndroidAgentLogger, stopped::add)
    }

    @After
    fun tearDown() { bridge.close() }

    @Test
    fun etaChatUsesDirectConversationAndPrivateImmutableStopAction() {
        bridge.start(request("run", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            AgentUiHandoffPayload("conversation").toJson()))
        ShadowLooper.idleMainLooper()
        val snapshot = snapshot()
        val view = shadowOf(snapshot.open).savedIntent
        assertEquals("conversation", view.getStringExtra(EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY))
        assertEquals(AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, view.getStringExtra(MainActivity.EXTRA_EXECUTION_SOURCE))
        assertEquals(ModuleConfig.ETA_PACKAGE, snapshot.stop.creatorPackage)
        assertTrue(snapshot.stop.isImmutable)
        snapshot.stop.send()
        ShadowLooper.idleMainLooper()
        assertEquals(listOf("run"), stopped)
    }

    @Test
    fun staleAndFinishedStopActionsCannotCancelReplacementTask() {
        bridge.start(request("old"))
        ShadowLooper.idleMainLooper()
        val old = snapshot().stop
        bridge.start(request("new"))
        ShadowLooper.idleMainLooper()
        old.send()
        ShadowLooper.idleMainLooper()
        assertTrue(stopped.isEmpty())
        val stop = snapshot().stop
        bridge.finish(AgentRuntimeWire.RunResult("old", true, ""))
        bridge.finish(AgentRuntimeWire.RunResult("new", true, "private answer"))
        ShadowLooper.idleMainLooper()
        stop.send()
        ShadowLooper.idleMainLooper()
        assertTrue(stopped.isEmpty())
        assertEquals(VivoIslandNotifications.State.COMPLETED, snapshot().state)
    }

    @Test
    fun phaseUpdatesAreCoalescedAndNeverSendRuntimeSecrets() {
        bridge.start(request("run"))
        ShadowLooper.idleMainLooper()
        val count = messages.size
        bridge.update("run", AgentEvent.ToolStarted(1, "call", "run_command", "secret arguments", "secret command"))
        bridge.update("run", AgentEvent.ProviderRequestStarted(2))
        assertEquals(count, messages.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(650))
        assertEquals(count + 1, messages.size)
        assertEquals(VivoIslandWire.Phase.THINKING, snapshot().progress.phase)
        assertFalse(messages.any { it.data.toString().contains("secret") })
    }

    @Test
    fun missingHookDoesNotSendCommandsToNativeShortcutBinder() {
        val native = Binder().apply { attachInterface(null, "com.vivo.ai.copilot.business.si.ISiTaskAidlInterface") }
        shadowOf(context).setComponentNameAndServiceForBindService(VivoIslandWire.serviceIntent().component, native)
        bridge.start(request("run"))
        ShadowLooper.idleMainLooper()
        bridge.update("run", AgentEvent.ProviderRequestStarted(1))
        bridge.finish(AgentRuntimeWire.RunResult("run", true, ""))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(650))
        assertTrue(messages.isEmpty())
        assertTrue(shadowOf(context).boundServiceConnections.isEmpty())
    }

    @Test
    fun vivoTakeoverDoesNotCreateADuplicateBridgeNotification() {
        bridge.start(request("vivo", VivoHandoff.SOURCE))
        ShadowLooper.idleMainLooper()
        assertTrue(messages.isEmpty())
        assertTrue(shadowOf(context).boundServiceConnections.isEmpty())
    }

    @Test
    fun voiceAndAutomationUseTheirArchiveKeysAndCancellationState() {
        for (source in listOf(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, "automation")) {
            bridge.start(request(source, source, AgentExternalArchivePayload("secret prompt", "key-$source", "Eta").toJson()))
            ShadowLooper.idleMainLooper()
            val view = shadowOf(snapshot().open).savedIntent
            assertEquals(source, view.getStringExtra(MainActivity.EXTRA_EXECUTION_SOURCE))
            assertEquals("key-$source", view.getStringExtra(EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY))
            bridge.finish(AgentRuntimeWire.RunResult(source, false, "", "已停止"))
            ShadowLooper.idleMainLooper()
            assertEquals(VivoIslandNotifications.State.CANCELLED, snapshot().state)
        }
    }

    @Test
    fun completionBeforeBindingPublishesOnlyLatestTerminalSnapshot() {
        bridge.start(request("run"))
        bridge.finish(AgentRuntimeWire.RunResult("run", false, "", "failure"))
        ShadowLooper.idleMainLooper()
        assertEquals(1, messages.size)
        assertEquals(VivoIslandNotifications.State.FAILED, snapshot().state)
    }

    @Test
    fun islandStopCancelsTheActualRuntimeControllerAndCannotCancelItsReplacement() {
        val lifecycle = Robolectric.buildService(AgentRuntimeService::class.java).create()
        val service = lifecycle.get()
        try {
            val activeField = AgentRuntimeService::class.java.getDeclaredField("activeSession").apply { isAccessible = true }
            val bridgeField = AgentRuntimeService::class.java.getDeclaredField("islandBridge").apply { isAccessible = true }
            val serviceBridge = bridgeField.get(service) as AgentRuntimeIslandBridge
            val session = AgentRuntimeSession("runtime-run")
            activeField.set(service, session)
            serviceBridge.start(request(session.runId))
            ShadowLooper.idleMainLooper()
            val stop = snapshot().stop
            stop.send()
            ShadowLooper.idleMainLooper()
            assertTrue(session.controller.isCancelled)
            val replacement = AgentRuntimeSession("replacement")
            activeField.set(service, replacement)
            stop.send()
            ShadowLooper.idleMainLooper()
            assertFalse(replacement.controller.isCancelled)
        } finally {
            lifecycle.destroy()
        }
    }

    private fun snapshot() = VivoIslandWire.fromBundle(messages.last().data)!!
    private fun request(
        runId: String,
        source: String = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
        payload: String = AgentUiHandoffPayload("conversation").toJson(),
    ) = AgentRuntimeWire.RunRequest(
        runId, "secret prompt", AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "secret key", model = "stub", systemPrompt = "secret system prompt",
        ), emptyList(), handoff = AgentRuntimeWire.EntryHandoff(runId, source, payload),
    )
}
