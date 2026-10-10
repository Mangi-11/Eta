package io.github.mangi.eta.agent.display

/** Unsupported dumps remain unknown; another display's ANR cannot stop this display. */
internal object VirtualScreenInputHealth {
    enum class Status { RESPONSIVE, UNRESPONSIVE, UNKNOWN }

    data class Result(
        val status: Status,
        val window: String? = null,
        val pid: Int? = null,
    )

    fun parse(dump: String, displayId: Int, packageName: String): Result {
        if (displayId <= 0 || packageName.isBlank()) return Result(Status.UNKNOWN)
        val focusedSection = dump.substringAfter("  FocusedWindows:", "")
            .substringBefore("  FocusRequests:")
        val focus = focusedSection.lines().firstOrNull { line ->
            Regex("\\bdisplayId=$displayId(?:,|\\s)").containsMatchIn(line)
        }?.let { Regex("name='([^']+)'").find(it)?.groupValues?.get(1) }
            ?: return Result(Status.UNKNOWN)
        val component = Regex("(?:^|\\s)${Regex.escape(packageName)}/")
        if (!component.containsMatchIn(focus)) return Result(Status.UNKNOWN)
        val windowLine = dump.lineSequence().firstOrNull { line ->
            Regex("^\\s*\\d+: name=").containsMatchIn(line) && line.contains("name=$focus, id=") &&
                Regex("\\bdisplayId=$displayId,").containsMatchIn(line)
        } ?: return Result(Status.UNKNOWN)
        if (Regex("\\b(NOT_VISIBLE|NO_INPUT_CHANNEL)\\b").containsMatchIn(windowLine)) {
            return Result(Status.UNKNOWN)
        }
        val pid = Regex("\\bownerPid=(\\d+)").find(windowLine)?.groupValues?.get(1)?.toIntOrNull()
        val connection = dump.substringAfter("  Connections:", "").lineSequence()
            .firstOrNull { it.contains("channelName='$focus',") } ?: return Result(Status.UNKNOWN, focus, pid)
        val responsive = Regex("\\bresponsive=(true|false)\\b").find(connection)?.groupValues?.get(1)
        return Result(when (responsive) {
            "true" -> Status.RESPONSIVE
            "false" -> Status.UNRESPONSIVE
            else -> Status.UNKNOWN
        }, focus, pid)
    }
}
