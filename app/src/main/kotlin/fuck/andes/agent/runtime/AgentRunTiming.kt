package fuck.andes.agent.runtime

import android.os.SystemClock
import fuck.andes.core.AgentLogger

/** 记录首请求关键边界，区分本地准备、网络握手和模型首 Token 延迟。 */
internal class AgentRunTiming(
    private val logger: AgentLogger,
) {
    private val runStartedAt = SystemClock.elapsedRealtime()
    private val requestStartedAt = mutableMapOf<Int, Long>()
    private val responseStartedAt = mutableMapOf<Int, Long>()
    private val firstDeltaRounds = mutableSetOf<Int>()

    fun preparationFinished(skillCount: Int) {
        logger.debug {
            "Agent runtime preparation finished: elapsed_ms=${elapsedSince(runStartedAt)}, " +
                "skills=$skillCount"
        }
    }

    fun accept(event: AgentEvent) {
        when (event) {
            is AgentEvent.ProviderRequestStarted -> {
                requestStartedAt[event.round] = SystemClock.elapsedRealtime()
                logger.debug {
                    "Agent provider request started: round=${event.round}, " +
                        "run_elapsed_ms=${elapsedSince(runStartedAt)}"
                }
            }

            is AgentEvent.ProviderResponseStarted -> {
                responseStartedAt[event.round] = SystemClock.elapsedRealtime()
                logger.debug {
                    "Agent provider response headers received: round=${event.round}, " +
                        "request_elapsed_ms=${elapsedSince(requestStartedAt[event.round])}"
                }
            }

            is AgentEvent.AssistantBlockDelta -> {
                if (firstDeltaRounds.add(event.round)) {
                    logger.debug {
                        "Agent provider first delta received: round=${event.round}, " +
                            "request_elapsed_ms=${elapsedSince(requestStartedAt[event.round])}, " +
                            "headers_elapsed_ms=${elapsedSince(responseStartedAt[event.round])}"
                    }
                }
            }

            else -> Unit
        }
    }

    private fun elapsedSince(startedAt: Long?): Long =
        startedAt?.let { SystemClock.elapsedRealtime() - it } ?: -1L
}
