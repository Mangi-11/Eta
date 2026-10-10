package io.github.mangi.eta.hook.vivo

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import io.github.mangi.eta.agent.overlay.toolDisplayNameResource
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.core.ModuleConfig

/** Only public task phases and immutable actions cross into Copilot, never runtime request payloads. */
internal object VivoIslandWire {
    const val ACTION_BIND = "io.github.mangi.eta.vivo.action.BIND_ISLAND"
    const val SERVICE = "com.vivo.ai.copilot.business.si.widgets.service.WidgetTaskService"
    const val MSG_RENDER = 1

    fun serviceIntent(): Intent = Intent(ACTION_BIND).setComponent(
        ComponentName(ModuleConfig.VIVO_COPILOT_PACKAGE, SERVICE),
    )

    enum class Phase { PREPARING, THINKING, ANSWERING, TOOL, RETRYING, COMPACTING }

    data class Progress(val phase: Phase, val tool: String = "", val virtualScreen: Boolean = false) {
        companion object {
            fun from(event: AgentEvent, virtualScreen: Boolean = false): Progress? = when (event) {
                is AgentEvent.RunStarted, is AgentEvent.RoundStarted,
                is AgentEvent.ModelRequestInterrupted,
                is AgentEvent.ProviderRequestStarted, is AgentEvent.ProviderResponseStarted,
                is AgentEvent.ToolFinished, is AgentEvent.HostedToolFinished -> Progress(Phase.THINKING)
                is AgentEvent.ToolStarted -> tool(event.name)
                is AgentEvent.HostedToolStarted -> tool(event.name)
                is AgentEvent.ModelRetryScheduled -> Progress(Phase.RETRYING)
                is AgentEvent.ContextCompaction -> Progress(
                    if (event.phase == AgentEvent.ContextCompaction.PHASE_STARTED) Phase.COMPACTING else Phase.THINKING,
                )
                is AgentEvent.AssistantBlockStart -> when (event.kind) {
                    AgentEvent.AssistantBlockKind.TEXT -> Progress(Phase.ANSWERING)
                    AgentEvent.AssistantBlockKind.THINKING -> Progress(Phase.THINKING)
                    AgentEvent.AssistantBlockKind.TOOL_CALL -> null
                }
                else -> null
            }?.copy(virtualScreen = virtualScreen)

            private fun tool(name: String) = Progress(Phase.TOOL, name.takeIf {
                toolDisplayNameResource(it) != null
            }.orEmpty())
        }
    }

    data class Snapshot(
        val owner: String,
        val sequence: Long,
        val token: String,
        val runId: String,
        val state: VivoIslandNotifications.State,
        val progress: Progress,
        val open: PendingIntent,
        val stop: PendingIntent,
        val clear: Boolean = false,
    ) {
        fun toBundle(): Bundle = Bundle().apply {
            putInt("version", 1)
            putString("owner", owner)
            putLong("sequence", sequence)
            putString("token", token)
            putString("run", runId)
            putString("state", state.name)
            putString("phase", progress.phase.name)
            putString("tool", progress.tool)
            putBoolean("virtual_screen", progress.virtualScreen)
            putParcelable("open", open)
            putParcelable("stop", stop)
            putBoolean("clear", clear)
        }
    }

    fun fromBundle(bundle: Bundle): Snapshot? = runCatching {
        if (bundle.getInt("version") != 1) return null
        fun identifier(key: String, limit: Int): String = bundle.getString(key).orEmpty().also {
            require(it.isNotBlank() && it.length <= limit)
        }
        val open = bundle.getParcelable("open", PendingIntent::class.java) ?: return null
        val stop = bundle.getParcelable("stop", PendingIntent::class.java) ?: return null
        require(open.creatorPackage == ModuleConfig.ETA_PACKAGE && open.isImmutable && open.isActivity)
        require(stop.creatorPackage == ModuleConfig.ETA_PACKAGE && stop.isImmutable && stop.isBroadcast)
        val phase = Phase.valueOf(identifier("phase", 24))
        val tool = bundle.getString("tool").orEmpty().takeIf {
            it.length <= 100 && toolDisplayNameResource(it) != null
        }.orEmpty()
        Snapshot(
            owner = identifier("owner", 64), sequence = bundle.getLong("sequence").also { require(it > 0) },
            token = identifier("token", 64), runId = identifier("run", 128),
            state = VivoIslandNotifications.State.valueOf(identifier("state", 24)),
            progress = Progress(phase, tool, bundle.getBoolean("virtual_screen")),
            open = open, stop = stop, clear = bundle.getBoolean("clear"),
        )
    }.getOrNull()
}
