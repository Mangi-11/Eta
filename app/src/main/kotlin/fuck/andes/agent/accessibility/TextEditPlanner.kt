package fuck.andes.agent.accessibility

/** 按 Android 文本选区（UTF-16 offset）生成一次真实“键入”后的内容与光标位置。 */
internal object TextEditPlanner {
    data class Plan(
        val text: String,
        val cursor: Int,
    )

    fun canSafelyReconstruct(
        password: Boolean,
        textAvailable: Boolean,
        textLength: Int,
        selectionStart: Int,
        selectionEnd: Int,
    ): Boolean {
        if (password || !textAvailable || textLength < 0) return false
        if (selectionStart in 0..textLength && selectionEnd in 0..textLength) return true
        // 部分空输入控件以 -1 表示尚未创建光标；空文本仍只有插入点 0。
        return textLength == 0 && selectionStart <= 0 && selectionEnd <= 0
    }

    fun insertAtSelection(
        currentText: String,
        insertedText: String,
        selectionStart: Int,
        selectionEnd: Int,
    ): Plan? {
        val selectionIsValid =
            selectionStart in 0..currentText.length && selectionEnd in 0..currentText.length
        if (!selectionIsValid && currentText.isNotEmpty()) return null
        val start = if (selectionIsValid) selectionStart else 0
        val end = if (selectionIsValid) selectionEnd else 0
        val lower = minOf(start, end)
        val upper = maxOf(start, end)
        return Plan(
            text = currentText.substring(0, lower) + insertedText + currentText.substring(upper),
            cursor = lower + insertedText.length,
        )
    }
}
