package fuck.andes.agent.device

/** 明确暴露另一滚动轴时禁止手势兜底，避免把滚动误变成列表项侧滑。 */
internal object ScrollAxisContract {
    fun exposesOnlyOppositeAxis(
        requestedAxis: ScrollAxis,
        hasVerticalActions: Boolean,
        hasHorizontalActions: Boolean,
    ): Boolean = when (requestedAxis) {
        ScrollAxis.VERTICAL -> hasHorizontalActions && !hasVerticalActions
        ScrollAxis.HORIZONTAL -> hasVerticalActions && !hasHorizontalActions
    }

    /** FORWARD/BACKWARD 没有轴语义；仅在已有明确纵向且无横向证据时可作纵向兼容。 */
    fun mayTreatLegacyActionsAsVertical(
        requestedAxis: ScrollAxis,
        hasVerticalActions: Boolean,
        hasHorizontalActions: Boolean,
    ): Boolean =
        requestedAxis == ScrollAxis.VERTICAL &&
            hasVerticalActions &&
            !hasHorizontalActions
}
