package fuck.andes.agent.terminal

/** Root 侧 BusyBox 只能在 su 进程中探测，App 进程通常无权遍历 /data/adb。 */
internal object AndroidBusyBox {
    private val candidates = listOf(
        "/data/adb/magisk/busybox",
        "/data/adb/ksu/bin/busybox",
        "/data/adb/ap/bin/busybox",
        "/system/xbin/busybox",
        "/system/bin/busybox",
    )

    fun discoveryScript(variable: String = "eta_busybox"): String {
        require(variable.matches(Regex("[a-z_][a-z0-9_]*"))) { "非法 Shell 变量名" }
        val quotedCandidates = candidates.joinToString(" ") { shellQuote(it) }
        return "$variable=''; " +
            "for eta_candidate in $quotedCandidates; do " +
            "if [ -x \"${'$'}eta_candidate\" ]; then $variable=\"${'$'}eta_candidate\"; break; fi; " +
            "done; " +
            "if [ -z \"${'$'}$variable\" ] && command -v busybox >/dev/null 2>&1; then " +
            "$variable=${'$'}(command -v busybox); fi"
    }
}
