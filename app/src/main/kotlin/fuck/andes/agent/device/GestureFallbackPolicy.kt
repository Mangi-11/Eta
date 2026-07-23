package fuck.andes.agent.device

/** 只有确认手势从未提交给系统时，才允许改用 Root 重放。 */
internal object GestureFallbackPolicy {
    fun mayFallbackToRoot(errorCode: String): Boolean =
        errorCode == "GESTURE_NOT_DISPATCHED"
}
