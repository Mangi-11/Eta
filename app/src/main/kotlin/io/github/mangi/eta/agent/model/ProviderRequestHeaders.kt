package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CustomHeader
import java.util.UUID
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object ProviderRequestHeaders {
    fun mergeInto(
        builder: Headers.Builder,
        baseUrl: String,
        customHeaders: List<CustomHeader>,
        sessionId: String = UUID.randomUUID().toString(),
    ) {
        builder.set("User-Agent", "Eta")
        CustomHeaderFilter.mergeInto(builder, customHeaders)
        if (baseUrl.toHttpUrlOrNull()?.host == "opencode.ai") {
            // 会话头由 Runtime 持有，避免固定自定义值把所有对话合并到同一路由。
            builder.set(
                "x-opencode-session",
                UUID.nameUUIDFromBytes(sessionId.toByteArray(Charsets.UTF_8)).toString(),
            )
        }
    }
}
