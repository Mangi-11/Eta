package fuck.andes.agent.device

/** Root 进程超时不能证明已经发给系统的输入动作没有执行。 */
internal object ShellActionOutcomePolicy {
    enum class Outcome {
        SUCCEEDED,
        FAILED,
        TIMED_OUT,
    }

    fun classify(exitCode: Int): Outcome = when (exitCode) {
        0 -> Outcome.SUCCEEDED
        PROCESS_TIMEOUT_EXIT_CODE -> Outcome.TIMED_OUT
        else -> Outcome.FAILED
    }

    const val PROCESS_TIMEOUT_EXIT_CODE = -2
}
