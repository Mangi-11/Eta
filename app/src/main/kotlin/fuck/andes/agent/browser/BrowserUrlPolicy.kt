package fuck.andes.agent.browser

import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * 浏览器网络边界的纯逻辑部分。
 *
 * DNS 查询由会话层完成；这里仅负责规范化 URL、判断解析结果是否可访问，以及生成不会直接
 * 暴露常见凭据参数的展示地址。这样测试不依赖 Android 或真实网络。
 */
internal object BrowserUrlPolicy {
    private const val MAX_DISPLAY_URL_CHARS = 240
    private const val MAX_URL_CHARS = 4_096
    private val safeDisplayQueryKey = Regex("[A-Za-z][A-Za-z0-9_-]{0,31}")
    private val sensitivePathLabel = Regex(
        "(?i)(access|auth|authorization|bearer|code|credential|jwt|key|password|refresh|secret|session|signature|sig|token)"
    )
    private val highEntropyPathSegment = Regex("(?i)([a-f0-9]{24,}|[a-z0-9_-]{32,})")

    data class Decision(
        val allowed: Boolean,
        val normalizedUrl: String = "",
        val displayUrl: String = "",
        val host: String = "",
        val errorCode: String? = null,
        val message: String? = null,
    )

    fun inspect(rawUrl: String): Decision {
        val input = rawUrl.trim()
        if (input.isEmpty()) return denied("EMPTY_URL", "网址不能为空")
        if (input.length > MAX_URL_CHARS) return denied("URL_TOO_LONG", "网址过长")
        if (input.any { it.isISOControl() } || '\\' in input) {
            return denied("INVALID_URL", "网址格式不安全")
        }

        val uri = runCatching { URI(input) }.getOrNull()
            ?: return denied("INVALID_URL", "网址格式无效")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https") {
            return denied("UNSUPPORTED_SCHEME", "Agent 浏览器仅允许 https 网址")
        }
        if (uri.rawUserInfo != null || uri.rawAuthority?.contains('@') == true) {
            return denied("URL_USERINFO_BLOCKED", "网址不能包含用户名或密码")
        }

        val authority = parseAuthority(uri.rawAuthority)
            ?: return denied("INVALID_HOST", "网址缺少有效主机名")
        if (authority.port !in -1..65535 || authority.port == 0) {
            return denied("INVALID_PORT", "网址端口无效")
        }

        val normalizedHost = normalizeHost(authority.host)
            ?: return denied("INVALID_HOST", "网址主机名无效")
        if (isLocalHostname(normalizedHost)) {
            return denied("LOCAL_HOST_BLOCKED", "不允许访问本机或局域网主机")
        }

        val literal = parseAddress(normalizedHost)
        if (literal != null && !isPublicAddressBytes(literal)) {
            return denied("PRIVATE_ADDRESS_BLOCKED", "不允许访问本地、私有或保留地址")
        }
        if (literal == null && looksLikeNumericAddress(normalizedHost)) {
            return denied("AMBIGUOUS_ADDRESS_BLOCKED", "不允许使用非标准数字地址")
        }

        val effectivePort = when {
            scheme == "http" && authority.port == 80 -> -1
            scheme == "https" && authority.port == 443 -> -1
            else -> authority.port
        }
        val hostForUrl = if (':' in normalizedHost) "[$normalizedHost]" else normalizedHost
        val normalized = buildString {
            append(scheme).append("://").append(hostForUrl)
            if (effectivePort >= 0) append(':').append(effectivePort)
            append(uri.rawPath.orEmpty().ifEmpty { "/" })
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
        if (runCatching { URI(normalized) }.isFailure) {
            return denied("INVALID_URL", "网址格式无效")
        }
        return Decision(
            allowed = true,
            normalizedUrl = normalized,
            displayUrl = redactForDisplay(normalized),
            host = normalizedHost,
        )
    }

    /** 所有 DNS 答案都必须是公网地址，混合公网/私网的答案同样拒绝。 */
    fun inspectResolved(decision: Decision, resolvedAddresses: Collection<String>): Decision {
        if (!decision.allowed) return decision
        if (resolvedAddresses.isEmpty()) {
            return decision.copy(
                allowed = false,
                errorCode = "DNS_RESOLUTION_FAILED",
                message = "无法解析该网址的主机名"
            )
        }
        if (resolvedAddresses.any { !isPublicAddress(it) }) {
            return decision.copy(
                allowed = false,
                errorCode = "PRIVATE_ADDRESS_BLOCKED",
                message = "网址解析到了本地、私有或保留地址"
            )
        }
        return decision
    }

    fun isPublicAddress(address: String): Boolean {
        val bytes = parseAddress(address.trim().substringBefore('%')) ?: return false
        return isPublicAddressBytes(bytes)
    }

    fun redactForDisplay(rawUrl: String): String {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return "无效网址"
        val rawAuthority = uri.rawAuthority ?: return "无效网址"
        val authority = parseAuthority(rawAuthority) ?: return "无效网址"
        val host = normalizeHost(authority.host) ?: return "无效网址"
        val hostForDisplay = if (':' in host) "[$host]" else host
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val port = when {
            authority.port < 0 -> ""
            scheme == "http" && authority.port == 80 -> ""
            scheme == "https" && authority.port == 443 -> ""
            else -> ":${authority.port}"
        }
        val redactedQuery = uri.rawQuery?.split('&')?.joinToString("&") { pair ->
            val rawKey = pair.substringBefore('=')
            if ('=' in pair && safeDisplayQueryKey.matches(rawKey)) "$rawKey=…" else "…"
        }
        var redactNextPathSegment = false
        val redactedPath = uri.rawPath.orEmpty().ifEmpty { "/" }
            .split('/')
            .joinToString("/") { segment ->
                val shouldRedact = redactNextPathSegment || highEntropyPathSegment.matches(segment)
                redactNextPathSegment = sensitivePathLabel.matches(segment)
                if (shouldRedact) "…" else segment
            }
        val display = buildString {
            append(scheme).append("://").append(hostForDisplay).append(port)
            append(redactedPath)
            if (!redactedQuery.isNullOrEmpty()) append('?').append(redactedQuery)
        }
        return if (display.length <= MAX_DISPLAY_URL_CHARS) {
            display
        } else {
            display.take(MAX_DISPLAY_URL_CHARS - 1) + "…"
        }
    }

    /** 模型与运行摘要只获得 HTTPS origin，不暴露可能携带凭据的 path/query/fragment。 */
    fun originForModel(rawUrl: String): String {
        val decision = inspect(rawUrl)
        if (!decision.allowed) return ""
        val uri = runCatching { URI(decision.normalizedUrl) }.getOrNull() ?: return ""
        return "${uri.scheme}://${uri.rawAuthority}/"
    }

    /** 返回 captcha/cloudflare/http_403/http_429，null 表示未发现明确挑战。 */
    fun detectRiskChallenge(
        statusCode: Int? = null,
        title: String = "",
        url: String = "",
        pageText: String = "",
    ): String? {
        val sample = "$title\n$url\n${pageText.take(8_000)}".lowercase(Locale.ROOT)
        if (
            "cloudflare" in sample ||
            "cf-chl" in sample ||
            "challenge-platform" in sample ||
            "attention required" in sample ||
            "just a moment" in sample
        ) {
            return "cloudflare"
        }
        if (
            "captcha" in sample ||
            "recaptcha" in sample ||
            "hcaptcha" in sample ||
            "verify you are human" in sample ||
            "are you a robot" in sample ||
            "unusual traffic" in sample ||
            "人机验证" in sample ||
            "安全验证" in sample
        ) {
            return "captcha"
        }
        return when (statusCode) {
            403 -> "http_403"
            429 -> "http_429"
            else -> null
        }
    }

    private fun denied(code: String, message: String): Decision =
        Decision(allowed = false, errorCode = code, message = message)

    private data class Authority(val host: String, val port: Int)

    private fun parseAuthority(rawAuthority: String?): Authority? {
        val raw = rawAuthority?.trim().orEmpty()
        if (raw.isEmpty() || '@' in raw) return null
        if (raw.startsWith('[')) {
            val closing = raw.indexOf(']')
            if (closing <= 1) return null
            val host = raw.substring(1, closing)
            val suffix = raw.substring(closing + 1)
            val port = when {
                suffix.isEmpty() -> -1
                suffix.startsWith(':') && suffix.drop(1).all(Char::isDigit) ->
                    suffix.drop(1).toIntOrNull() ?: return null
                else -> return null
            }
            return Authority(host, port)
        }

        if (raw.count { it == ':' } > 1) return null
        val colon = raw.lastIndexOf(':')
        if (colon < 0) return Authority(raw, -1)
        val portText = raw.substring(colon + 1)
        if (portText.isEmpty() || !portText.all(Char::isDigit)) return null
        return Authority(raw.substring(0, colon), portText.toIntOrNull() ?: return null)
    }

    private fun normalizeHost(rawHost: String): String? {
        val raw = rawHost.trim().trimEnd('.')
        if (raw.isEmpty() || '%' in raw) return null
        if (':' in raw) {
            return if (parseAddress(raw) != null) raw.lowercase(Locale.ROOT) else null
        }
        val ascii = runCatching {
            IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES)
        }.getOrNull()?.lowercase(Locale.ROOT) ?: return null
        if (ascii.length > 253 || ascii.split('.').any { it.isEmpty() || it.length > 63 }) return null
        return ascii
    }

    private fun isLocalHostname(host: String): Boolean {
        if (parseAddress(host) != null) return false
        if ('.' !in host) return true
        return host == "localhost" ||
            host.endsWith(".localhost") ||
            host == "home.arpa" ||
            host.endsWith(".home.arpa") ||
            host.endsWith(".local") ||
            host.endsWith(".lan") ||
            host.endsWith(".internal") ||
            host.endsWith(".intranet")
    }

    private fun looksLikeNumericAddress(host: String): Boolean =
        host.all { it.isDigit() || it == '.' } || host.startsWith("0x", ignoreCase = true)

    private fun parseAddress(value: String): ByteArray? {
        val input = value.removePrefix("[").removeSuffix("]")
        parseIpv4(input)?.let { return it }
        if (':' !in input || '%' in input) return null
        return runCatching { InetAddress.getByName(input).address }
            .getOrNull()
            ?.takeIf { it.size == 4 || it.size == 16 }
    }

    private fun parseIpv4(value: String): ByteArray? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val output = ByteArray(4)
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty() || !part.all(Char::isDigit)) return null
            // 拒绝可能被其他 URL 解析器按八进制解释的形式。
            if (part.length > 1 && part.startsWith('0')) return null
            val number = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            output[index] = number.toByte()
        }
        return output
    }

    private fun isPublicAddressBytes(bytes: ByteArray): Boolean {
        val address = runCatching { InetAddress.getByAddress(bytes) }.getOrNull() ?: return false
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }
        return when (bytes.size) {
            4 -> isPublicIpv4(bytes)
            16 -> isPublicIpv6(bytes)
            else -> false
        }
    }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        val c = bytes[2].toInt() and 0xff
        return when {
            a == 0 -> false
            a == 10 -> false
            a == 100 && b in 64..127 -> false // CGNAT
            a == 127 -> false
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 0 && c == 0 -> false
            a == 192 && b == 0 && c == 2 -> false
            a == 192 && b == 168 -> false
            a == 198 && b in 18..19 -> false
            a == 198 && b == 51 && c == 100 -> false
            a == 203 && b == 0 && c == 113 -> false
            a >= 224 -> false // multicast 与保留地址
            else -> true
        }
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        if (bytes.all { it == 0.toByte() }) return false
        if (bytes.dropLast(1).all { it == 0.toByte() } && bytes.last() == 1.toByte()) return false
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        if (first and 0xe0 != 0x20) return false // 当前公网单播分配 2000::/3
        if (first == 0xff) return false // multicast
        if (first and 0xfe == 0xfc) return false // unique local
        if (first == 0xfe && second and 0xc0 == 0x80) return false // link-local

        // IPv4-mapped IPv6；JVM 通常已还原成 4 字节，此处覆盖其他实现。
        val mapped = bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
        if (mapped) return isPublicIpv4(bytes.copyOfRange(12, 16))

        val compatible = bytes.take(12).all { it == 0.toByte() }
        if (compatible) return isPublicIpv4(bytes.copyOfRange(12, 16))

        // 文档、基准测试和不应作为公网目标的特殊前缀。
        if (first == 0x20 && second == 0x01) {
            val third = bytes[2].toInt() and 0xff
            val fourth = bytes[3].toInt() and 0xff
            if (third == 0x0d && fourth == 0xb8) return false // documentation
            if (third == 0x00 && fourth == 0x02) return false // benchmarking
            if (third == 0x00 && fourth == 0x00) return false // Teredo/special registry
            val specialPrefix = fourth and 0xf0
            if (third == 0x00 && (specialPrefix == 0x10 || specialPrefix == 0x20 || specialPrefix == 0x30)) {
                return false
            }
        }

        // 6to4 地址携带 IPv4 目标，避免用其绕过私网判断。
        if (first == 0x20 && second == 0x02) {
            return isPublicIpv4(bytes.copyOfRange(2, 6))
        }
        return true
    }
}
