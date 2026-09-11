package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.CustomHeader

/**
 * 自定义 HTTP Header 安全管理。
 *
 * 过滤掉会破坏 HTTP 协议或被框架自动管理的 header，并对敏感 header 做日志脱敏。
 */
internal object CustomHeaderFilter {

    /**
     * 禁止用户手动设置的 header 名称（大小写不敏感）。
     */
    private val FORBIDDEN_NAMES = setOf(
        "host",
        "content-length",
        "connection",
        "transfer-encoding",
        "content-encoding",
        "accept-encoding",
        "expect",
        "keep-alive",
        "proxy-connection",
        "upgrade",
        "authorization",
        "x-api-key",
        "anthropic-version"
    )

    /**
     * 日志中需要脱敏的 header 名称（大小写不敏感）。
     */
    private val SENSITIVE_NAMES = setOf(
        "authorization",
        "x-api-key",
        "api-key"
    )

    /**
     * 是否是被禁止的 header 名称。
     */
    fun isForbidden(name: String): Boolean =
        name.trim().isBlank() || name.trim().lowercase() in FORBIDDEN_NAMES

    /**
     * 过滤列表中的非法 header。
     */
    fun sanitize(headers: List<CustomHeader>): List<CustomHeader> =
        headers.filterNot { isForbidden(it.name) }

    fun validationError(headers: List<CustomHeader>): String? {
        val names = mutableSetOf<String>()
        headers.forEachIndexed { index, header ->
            val name = header.name.trim()
            val prefix = "第 ${index + 1} 个请求头"
            if (isForbidden(name)) return "${prefix}名称为空或由系统管理"
            if (!name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) {
                return "${prefix}名称包含无效字符"
            }
            if (header.value.any { it != '\t' && it !in ' '..'~' }) {
                return "${prefix}值只能包含可打印的 ASCII 字符或制表符"
            }
            if (!names.add(name.lowercase())) return "${prefix}名称重复（不区分大小写）"
        }
        return null
    }

    /**
     * 将自定义 header 合并到 OkHttp Headers Builder，后添加的覆盖先添加的同名 header。
     */
    fun mergeInto(
        builder: okhttp3.Headers.Builder,
        headers: List<CustomHeader>
    ) {
        sanitize(headers).forEach { header ->
            builder.set(header.name.trim(), header.value)
        }
    }

    /**
     * 为日志输出脱敏敏感 header。
     */
    fun redactForLog(headers: List<CustomHeader>): List<Pair<String, String>> =
        sanitize(headers).map { header ->
            val nameLower = header.name.lowercase()
            val value = if (nameLower in SENSITIVE_NAMES) {
                "***"
            } else {
                header.value
            }
            header.name to value
        }
}
