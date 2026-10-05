package io.github.mangi.eta.agent.phone

import android.content.Context
import android.os.Looper
import android.os.Process
import io.github.mangi.eta.agent.context.VivoPersonalContextQuery
import kotlin.system.exitProcess
import org.json.JSONObject

/** 由 Eta 的已授权 Root 执行器启动；仅接受固定业务动作，正文只走 stdin。 */
internal object PhoneCommandMain {
    const val MARKER = "ETA_PHONE_RESULT:"

    @JvmStatic
    @Suppress("DEPRECATION")
    fun main(arguments: Array<String>) {
        val result =
            try {
                if (Process.myUid() != 0) PhoneOperation.error("ROOT_REQUIRED", "需要 Root 身份")
                val input = System.`in`.readNBytes(128 * 1024 + 1)
                if (input.size > 128 * 1024) PhoneOperation.error("PHONE_INPUT_LIMIT", "请求超过大小限制")
                val request = JSONObject(input.toString(Charsets.UTF_8))
                if (request.optInt("version") != 1)
                    PhoneOperation.error("PHONE_PROTOCOL_UNSUPPORTED", "请求协议不兼容")
                val tool = PhoneOperation.text(request, "tool", true, 80)!!
                val vivo = request.optString("backend") == "vivo_personal"
                if (if (vivo) tool !in VivoPersonalContextQuery.tools else tool !in PhoneOperation.tools && tool != "capabilities")
                    PhoneOperation.error("UNKNOWN_TOOL", "未知一方应用操作")
                val userId = PhoneOperation.integer(request, "user_id", 0, 21474).toInt()
                if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
                val activityThread = Class.forName("android.app.ActivityThread")
                val thread = activityThread.getMethod("systemMain").invoke(null)
                val context = activityThread.getMethod("getSystemContext").invoke(thread) as Context
                val access = RootPhoneProviderAccess(userId)
                if (vivo) {
                    if (!android.os.Build.MANUFACTURER.equals("vivo", ignoreCase = true))
                        PhoneOperation.error("DEVICE_UNSUPPORTED", "需要 vivo 数据接口")
                    VivoPersonalContextQuery(access, userId).execute(tool, request.getJSONObject("arguments"))
                } else if (tool == "capabilities") {
                    val sources = JSONObject()
                    val queries = NativePersonalQueries(access, userId)
                    for (name in listOf("search_media", "search_notes", "search_messages")) {
                        sources.put(
                            name,
                            try {
                                queries.probe(name)
                            } catch (_: Exception) {
                                false
                            },
                        )
                    }
                    val calendar =
                        try {
                            access
                                .query(
                                    android.net.Uri.parse(
                                        "content://$userId@com.android.calendar/events"
                                    ),
                                    arrayOf("_id"),
                                    "0",
                                    null,
                                    null,
                                )
                                ?.use { true } ?: false
                        } catch (_: Exception) {
                            false
                        }
                    PhoneOperation.ok("capabilities")
                        .put("version", 1)
                        .put("caller_package", access.callerPackage)
                        .put("calendar", calendar)
                        .put("sources", sources)
                } else
                    PhoneOperations.execute(
                        context,
                        tool,
                        request.getJSONObject("arguments"),
                        userId,
                        access,
                    )
            } catch (failure: PhoneOperationFailure) {
                PhoneOperation.failure(failure.code, failure.message ?: "请求无效")
            } catch (_: Throwable) {
                PhoneOperation.failure("PHONE_BRIDGE_UNAVAILABLE", "当前系统无法启动原生应用操作通道")
            }
        val encoded = result.toString()
        println(
            MARKER +
                if (encoded.toByteArray(Charsets.UTF_8).size <= 512 * 1024) encoded
                else
                    PhoneOperation.failure("PHONE_OUTPUT_LIMIT", "结果超过大小限制，请先查询当前状态")
                        .put("status", "unconfirmed")
        )
        exitProcess(0)
    }
}
