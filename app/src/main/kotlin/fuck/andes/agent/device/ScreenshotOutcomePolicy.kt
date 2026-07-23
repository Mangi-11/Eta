package fuck.andes.agent.device

internal enum class ScreenshotQuality(val wireName: String) {
    NOT_REQUESTED("not_requested"),
    COMPLETE("complete"),
    PARTIAL("partial"),
    FAILED("failed"),
}

internal object ScreenshotOutcomePolicy {
    fun classify(
        requested: Boolean,
        hasImage: Boolean,
        complete: Boolean,
    ): ScreenshotQuality = when {
        !requested -> ScreenshotQuality.NOT_REQUESTED
        hasImage && complete -> ScreenshotQuality.COMPLETE
        hasImage -> ScreenshotQuality.PARTIAL
        else -> ScreenshotQuality.FAILED
    }

    fun mayFallbackToRoot(
        excludedPackagesPresent: Boolean,
        criticalWindowMissing: Boolean,
    ): Boolean = !excludedPackagesPresent && !criticalWindowMissing
}
