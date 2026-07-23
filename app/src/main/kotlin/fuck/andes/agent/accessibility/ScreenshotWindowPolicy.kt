package fuck.andes.agent.accessibility

/** 截图窗口筛选必须保持“模型看到的”和实际接收触摸的窗口一致。 */
internal object ScreenshotWindowPolicy {
    enum class Decision {
        CAPTURE,
        EXCLUDE,
        BLOCK_UNKNOWN,
    }

    fun decide(
        isAccessibilityOverlay: Boolean,
        isApplicationWindow: Boolean,
        active: Boolean,
        focused: Boolean,
        resolvedPackage: String?,
        ownPackage: String,
        excludedPackages: Set<String>,
    ): Decision {
        if (isAccessibilityOverlay && resolvedPackage == ownPackage) {
            return Decision.EXCLUDE
        }
        if (resolvedPackage in excludedPackages) return Decision.EXCLUDE
        if (
            excludedPackages.isNotEmpty() &&
            resolvedPackage.isNullOrBlank() &&
            (isApplicationWindow || active || focused)
        ) {
            return Decision.BLOCK_UNKNOWN
        }
        return Decision.CAPTURE
    }
}
