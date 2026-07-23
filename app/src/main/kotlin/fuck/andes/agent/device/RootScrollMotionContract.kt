package fuck.andes.agent.device

import kotlin.math.abs

/** 把屏幕节点位移转换成滚动位置位移；内容移动方向与滚动位置方向相反。 */
internal object RootScrollMotionContract {
    fun inferScrollDelta(
        contentAxisDeltas: List<Int>,
        minimumMotionPx: Int = 2,
    ): Int? {
        val meaningful = contentAxisDeltas
            .filter { delta -> abs(delta) >= minimumMotionPx }
        if (meaningful.size < MIN_ANCHORS) return null

        val positive = meaningful.filter { delta -> delta > 0 }
        val negative = meaningful.filter { delta -> delta < 0 }
        val dominant = when {
            positive.size > negative.size -> positive.sorted()
            negative.size > positive.size -> negative.sorted()
            else -> return null
        }
        if (dominant.size < MIN_ANCHORS || dominant.size * 3 < meaningful.size * 2) return null

        val median = dominant[dominant.size / 2]
        val tolerance = maxOf(MIN_SPREAD_TOLERANCE_PX, abs(median) / 2)
        val consistent = dominant.filter { delta -> abs(delta - median) <= tolerance }.sorted()
        if (consistent.size < MIN_ANCHORS || consistent.size * 3 < meaningful.size * 2) return null
        return -consistent[consistent.size / 2]
    }

    private const val MIN_ANCHORS = 2
    private const val MIN_SPREAD_TOLERANCE_PX = 8
}
