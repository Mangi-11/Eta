package io.github.mangi.eta.agent.tool.device

import org.json.JSONObject

/** 命令返回和状态读回分开报告，未知结果不能伪装成已改变。 */
internal object DeviceMutationResult {
    fun observed(
        tool: String,
        userId: Int,
        scope: String,
        before: Any?,
        after: Any?,
        expected: Any,
        commandAccepted: Boolean,
        failureCode: String? = null,
        matchesExpected: Boolean = after == expected,
    ): JSONObject {
        val verified = commandAccepted && after != null && matchesExpected
        val changed: Any = if (before != null && after != null) before != after else JSONObject.NULL
        return JSONObject()
            .put("ok", verified).put("tool", tool).put("user_id", userId).put("scope", scope)
            .put("before", before ?: JSONObject.NULL).put("after", after ?: JSONObject.NULL)
            .put("expected", expected).put("command_accepted", commandAccepted)
            .put("changed", changed).put("verified", verified)
            .put("status", if (verified) "verified" else if (commandAccepted) "unconfirmed" else "failed")
            .also { result ->
                if (!verified) {
                    result.put("code", failureCode ?: "STATE_CHANGE_UNCONFIRMED")
                    result.put("message", if (commandAccepted) "操作请求已提交，但未确认目标状态；请先检查状态，不要直接重复操作" else "系统接口未成功完成操作")
                }
            }
    }
}
