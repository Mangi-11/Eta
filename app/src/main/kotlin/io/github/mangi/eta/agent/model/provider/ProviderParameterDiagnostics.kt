package io.github.mangi.eta.agent.model.provider

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import org.json.JSONObject

/** 有界的服务端参数诊断，不承载失败分类或重试策略。 */
internal object ProviderParameterDiagnostics {
    /** 只投影协议枚举和已知字段位置，服务端 message/description 可能回显请求内容。 */
    fun describe(error: JSONObject?): String? {
        if (error == null) return null
        val codes = listOf("status", "code", "type").map(error::optString)
            .filter { it in parameterErrorCodes }.distinct()
        val fields = linkedSetOf<String>()
        safeParameterPath(error.optString("param"))?.let(fields::add)
        val details = error.optJSONArray("details")
        for (index in 0 until (details?.length() ?: 0)) {
            val violations = details?.optJSONObject(index)?.optJSONArray("fieldViolations") ?: continue
            for (violation in 0 until violations.length()) {
                safeParameterPath(violations.optJSONObject(violation)?.optString("field").orEmpty())
                    ?.let(fields::add)
                if (fields.size >= 3) break
            }
            if (fields.size >= 3) break
        }
        val separator = AppStrings.get(R.string.provider_param_separator, "；")
        return (codes.take(1) + fields.take(3).map {
            AppStrings.get(R.string.provider_param_field, "字段 %1\$s", it)
        }).joinToString(separator).ifEmpty { null }
    }

    private fun safeParameterPath(raw: String): String? {
        if (raw.length > 192 || !parameterPath.matches(raw)) return null
        val segments = raw.split('.')
        if (segments.first().substringBefore('[') !in requestParameterRoots) return null
        val customField = AppStrings.get(R.string.provider_param_custom_field, "<自定义字段>")
        return segments.joinToString(".") { segment ->
            if (segment.substringBefore('[') in publicParameterNames) segment else customField
        }
    }

    private val parameterErrorCodes = setOf(
        "INVALID_ARGUMENT", "FAILED_PRECONDITION", "invalid_request", "invalid_argument",
        "invalid_request_error", "unsupported_parameter", "model_not_found",
    )
    private val requestParameterRoots = setOf(
        "model", "input", "system_instruction", "tools", "generation_config", "response_format",
        "stream", "store", "previous_interaction_id", "background", "safety_settings", "service_tier",
    )
    private val publicParameterNames = requestParameterRoots + setOf(
        "content", "type", "role", "name", "description", "parameters", "properties", "items",
        "required", "additionalProperties", "oneOf", "anyOf", "allOf", "enum", "const", "format",
        "minimum", "maximum", "minItems", "maxItems", "minLength", "maxLength", "pattern",
        "thinking_level", "thinking_summaries", "tool_choice", "max_output_tokens", "temperature", "top_p",
        "text", "data", "uri", "mime_type", "schema", "arguments", "result", "call_id", "signature",
    )
    private val parameterPath = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\[[0-9]{1,6}])?(?:\\.[A-Za-z_][A-Za-z0-9_]*(?:\\[[0-9]{1,6}])?)*")
}
