package io.github.mangi.eta.hook.vivo

/** The first adapter is validated against OriginOS 6's BlueLM Copilot 6.8.5.3. */
internal object VivoTakeoverPolicy {
    const val SUPPORTED_VERSION_CODE = 68503L
    const val MAX_PROMPT_CHARS = 32_000

    fun isSupportedVersion(versionCode: Long): Boolean = versionCode == SUPPORTED_VERSION_CODE

    fun prompt(
        text: String,
        enabled: Boolean,
        requirePrefix: Boolean,
        hasAttachments: Boolean = false,
    ): String? {
        if (!enabled || hasAttachments) return null
        val input = text.trim()
        if (input.isBlank() || input.length > MAX_PROMPT_CHARS) return null
        val prefixed = when {
            input.startsWith("/agent%20") -> input.removePrefix("/agent%20").trim()
            input.startsWith("/agent") && input.getOrNull(6)?.isWhitespace() == true ->
                input.substring(6).trim()
            input == "/agent" -> ""
            else -> null
        }
        return (prefixed ?: input.takeUnless { requirePrefix })?.takeIf { it.isNotBlank() }
    }
}
