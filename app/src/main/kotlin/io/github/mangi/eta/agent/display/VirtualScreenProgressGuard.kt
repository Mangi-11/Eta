package io.github.mangi.eta.agent.display

import kotlin.math.abs

/** Counts attempts against observed state, rather than total model rounds. */
internal class VirtualScreenProgressGuard(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    data class Evidence(
        val scope: String,
        val nodes: String? = null,
        val image: Long? = null,
        val window: String? = null,
    )

    data class Action(
        val kind: String,
        val target: String = "",
        val x: Int? = null,
        val y: Int? = null,
        val endX: Int? = null,
        val endY: Int? = null,
    ) {
        fun sameTarget(other: Action): Boolean = kind == other.kind && target == other.target &&
            near(x, other.x) && near(y, other.y) && near(endX, other.endX) && near(endY, other.endY)

        private fun near(first: Int?, second: Int?): Boolean =
            if (first == null || second == null) first == second else abs(first.toLong() - second) <= 48
    }

    data class Decision(val attempts: Int, val repeatedAttempts: Int, val paused: Boolean) {
        val warning: Boolean get() = repeatedAttempts >= 2 || attempts >= 4
    }

    private var baseline: Evidence? = null
    private val attempts = mutableListOf<Action>()
    private var paused = false
    private var stalledSince: Long? = null
    private var unchangedObservations = 0

    fun beforeAction(action: Action, evidence: Evidence): Decision {
        observe(evidence)
        val repeats = attempts.count { it.sameTarget(action) }
        if (paused || repeats >= 3 || attempts.size >= 6) {
            paused = true
            return Decision(attempts.size, repeats, true)
        }
        attempts += action
        if (stalledSince == null) stalledSince = now()
        return Decision(attempts.size, repeats + 1, false)
    }

    fun onObservation(evidence: Evidence): Decision {
        observe(evidence)
        if (attempts.isNotEmpty()) unchangedObservations++
        if (unchangedObservations >= 8 && stalledSince?.let { now() - it >= 30_000 } == true) paused = true
        return Decision(attempts.size, attempts.maxOfOrNull { action -> attempts.count { it.sameTarget(action) } } ?: 0, paused)
    }

    fun observe(evidence: Evidence): Boolean {
        val previous = baseline
        val changed = previous == null || previous.scope != evidence.scope || changed(previous, evidence)
        if (changed) {
            baseline = evidence
            attempts.clear()
            stalledSince = null
            unchangedObservations = 0
            // Only a new run or manual input may resume a run already paused by the executor.
            if (previous?.scope != evidence.scope) paused = false
        } else if (previous.nodes == null && evidence.nodes != null) {
            baseline = previous.copy(nodes = evidence.nodes)
        }
        baseline?.let { baseline = it.copy(window = evidence.window ?: it.window,
            image = it.image ?: evidence.image) }
        return changed
    }

    private fun changed(before: Evidence, after: Evidence): Boolean {
        if (before.window != null && after.window != null && before.window != after.window) return true
        if (before.nodes != null && after.nodes != null) return before.nodes != after.nodes
        return before.image != null && after.image != null &&
            java.lang.Long.bitCount(before.image xor after.image) >= 8
    }
}
