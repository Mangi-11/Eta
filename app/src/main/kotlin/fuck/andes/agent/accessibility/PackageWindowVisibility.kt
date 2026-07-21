package fuck.andes.agent.accessibility

/** 查询主线程超时时不能把 UNKNOWN 当成窗口已经消失。 */
internal enum class PackageWindowVisibility {
    VISIBLE,
    GONE,
    UNKNOWN,
}
