package fuck.andes.agent.device

/** `wm size` 的 override 才是 input、screencap 与 UIAutomator 使用的当前逻辑坐标系。 */
internal object AndroidDisplaySizeParser {
    fun parse(output: String): Pair<Int, Int>? =
        parseLabel(output, "Override size") ?: parseLabel(output, "Physical size")

    private fun parseLabel(output: String, label: String): Pair<Int, Int>? {
        val match = Regex("""(?m)^\s*${Regex.escape(label)}:\s*(\d+)x(\d+)\s*$""")
            .find(output) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        return (width to height).takeIf { width > 0 && height > 0 }
    }
}
