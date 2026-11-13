package io.github.mangi.eta.ui.model

import kotlin.math.ceil
import kotlin.math.max

internal data class PromptTokenBreakdown(
    val promptTokens: Int,
    val uncachedInputTokens: Int,
    val cacheWriteTokens: Int,
    val cacheReadTokens: Int,
    val hasWriteTokens: Boolean,
)

internal data class CacheAttributionSummary(
    val totalInputTokens: Int = 0,
    val cachedTokens: Int = 0,
    val newContentTokens: Int = 0,
    val compactionRepayTokens: Int = 0,
    val ttlOrPrefixMissTokens: Int = 0,
    val identityValid: Boolean = true,
)

internal data class CompactionPaybackSummary(
    val archiveId: String = "",
    val tokensBefore: Int? = null,
    val compactionInputTokens: Int = 0,
    val compactionCachedTokens: Int = 0,
    val compactionCacheWriteTokens: Int = 0,
    val compactionOutputTokens: Int = 0,
    val postCompactionContextTokens: Int? = null,
    val turnsAfter: Int = 0,
    val paybackTurns: Int? = null,
    val paidBack: Boolean = false,
)

internal data class CacheDiagnosticsReport(
    val attribution: CacheAttributionSummary = CacheAttributionSummary(),
    val lastCompactionPayback: CompactionPaybackSummary? = null,
)

internal object CacheUsageDiagnostics {
    private const val MIN_CACHEABLE_BASELINE = 1_024
    private const val READ_PRICE_RATIO = 0.1
    private const val OUTPUT_PRICE_RATIO = 4.0

    fun resolvePromptBreakdown(usage: TokenUsageUi): PromptTokenBreakdown {
        val rawInput = (usage.inputTokens ?: 0).coerceAtLeast(0)
        val rawCacheRead = (usage.cachedTokens ?: 0).coerceAtLeast(0)
        val rawCacheWrite = (usage.cacheWriteTokens ?: 0).coerceAtLeast(0)
        val rawOut = (usage.outputTokens ?: 0).coerceAtLeast(0)
        val rawNonMiss = rawCacheRead + rawCacheWrite
        val prompt = when {
            rawInput >= rawNonMiss && rawInput > 0 -> rawInput
            rawInput > 0 && rawNonMiss > 0 -> rawInput + rawNonMiss
            else -> maxOf(rawInput, rawNonMiss, ((usage.contextTokens ?: 0) - rawOut).coerceAtLeast(0))
        }
        val cacheRead = rawCacheRead.coerceAtMost(prompt)
        val cacheWrite = rawCacheWrite.coerceAtMost((prompt - cacheRead).coerceAtLeast(0))
        val uncached = (prompt - cacheRead - cacheWrite).coerceAtLeast(0)
        return PromptTokenBreakdown(
            promptTokens = prompt,
            uncachedInputTokens = uncached,
            cacheWriteTokens = cacheWrite,
            cacheReadTokens = cacheRead,
            hasWriteTokens = usage.cacheWriteTokens != null,
        )
    }

    fun analyze(messages: List<AgentChatMessageUi>): CacheDiagnosticsReport {
        var totalInput = 0
        var totalCached = 0
        var totalNewContent = 0
        var totalCompRepay = 0
        var totalTtlMiss = 0
        var identityValid = true
        var prevEndInput: Int? = null

        data class ActiveCompaction(
            val archiveId: String,
            val tokensBefore: Int?,
            val compactionInput: Int,
            val compactionCached: Int,
            val compactionCacheWrite: Int,
            val compactionOutput: Int,
            var awaitingRebuild: Boolean = true,
            var postRebuildMiss: Int = 0,
            var postContextTokens: Int? = null,
            var turnsAfter: Int = 0,
        )

        var currentCompaction: ActiveCompaction? = null

        messages.forEach { message ->
            when (message) {
                is SystemNoticeMessageUi -> {
                    if (message.code == SystemNoticeCode.ContextCompaction && !message.running) {
                        val isFailure = message.detail?.contains("失败") == true ||
                            message.detail?.contains("中断") == true
                        if (!isFailure && (message.usage != null || message.archiveId.isNotBlank())) {
                            val breakdown = message.usage?.let(::resolvePromptBreakdown)
                            currentCompaction = ActiveCompaction(
                                archiveId = message.archiveId,
                                tokensBefore = message.tokensBefore ?: prevEndInput,
                                compactionInput = breakdown?.promptTokens ?: 0,
                                compactionCached = breakdown?.cacheReadTokens ?: 0,
                                compactionCacheWrite = breakdown?.cacheWriteTokens ?: 0,
                                compactionOutput = message.usage?.outputTokens ?: 0,
                            )
                        }
                    }
                }
                is AgentMessageUi -> {
                    val usage = message.usage ?: return@forEach
                    if (usage.inputTokens == null && usage.cachedTokens == null &&
                        usage.cacheWriteTokens == null && usage.contextTokens == null
                    ) {
                        return@forEach
                    }
                    val breakdown = resolvePromptBreakdown(usage)
                    val steps = (usage.stepCount ?: 1).coerceAtLeast(1)
                    val missed = (breakdown.promptTokens - breakdown.cacheReadTokens).coerceAtLeast(0)
                    val stepEndInput = if (steps <= 1) {
                        breakdown.promptTokens
                    } else {
                        val ctx = usage.contextTokens ?: (breakdown.promptTokens / steps)
                        val avgOut = (usage.outputTokens ?: 0) / steps
                        (ctx - avgOut).coerceAtLeast(0)
                    }

                    val active = currentCompaction
                    val newContent: Int
                    val compRepay: Int
                    val ttlRepay: Int
                    when {
                        prevEndInput == null -> {
                            newContent = missed
                            compRepay = 0
                            ttlRepay = 0
                        }
                        active != null && active.awaitingRebuild -> {
                            val preCompactionBaseline = maxOf(prevEndInput, active.tokensBefore ?: 0)
                            val growth = if (steps <= 1) {
                                (breakdown.promptTokens - preCompactionBaseline).coerceAtLeast(0)
                            } else {
                                0
                            }
                            newContent = minOf(missed, growth)
                            compRepay = (missed - newContent).coerceAtLeast(0)
                            ttlRepay = 0
                            active.awaitingRebuild = false
                            active.postRebuildMiss = compRepay
                            active.postContextTokens = stepEndInput.takeIf { it > 0 }
                        }
                        steps <= 1 -> {
                            val growth = (breakdown.promptTokens - prevEndInput).coerceAtLeast(0)
                            newContent = minOf(missed, growth)
                            compRepay = 0
                            ttlRepay = (missed - newContent).coerceAtLeast(0)
                        }
                        else -> {
                            if (breakdown.cacheReadTokens == 0 && prevEndInput >= MIN_CACHEABLE_BASELINE) {
                                ttlRepay = minOf(missed, prevEndInput)
                                newContent = (missed - ttlRepay).coerceAtLeast(0)
                            } else {
                                newContent = missed
                                ttlRepay = 0
                            }
                            compRepay = 0
                        }
                    }

                    if (breakdown.cacheReadTokens + newContent + compRepay + ttlRepay != breakdown.promptTokens) {
                        identityValid = false
                    }
                    totalInput += breakdown.promptTokens
                    totalCached += breakdown.cacheReadTokens
                    totalNewContent += newContent
                    totalCompRepay += compRepay
                    totalTtlMiss += ttlRepay
                    prevEndInput = stepEndInput
                    active?.let { it.turnsAfter += 1 }
                }
                else -> Unit
            }
        }

        val lastPayback = currentCompaction?.let { compact ->
            val beforeSize = compact.tokensBefore ?: compact.compactionInput
            val postSize = compact.postContextTokens ?: compact.compactionOutput
            val compactionMiss = (compact.compactionInput - compact.compactionCached).coerceAtLeast(0)
            val totalRepay = compactionMiss + compact.postRebuildMiss
            val writeRatio = if (compact.compactionCacheWrite > 0) 1.25 else 1.0
            val oneTimeCost = max(
                0.0,
                (writeRatio - READ_PRICE_RATIO) * totalRepay +
                    OUTPUT_PRICE_RATIO * compact.compactionOutput -
                    READ_PRICE_RATIO * beforeSize,
            )
            val savedPerTurn = max(0.0, (beforeSize - postSize) * READ_PRICE_RATIO)
            val paybackTurns = if (savedPerTurn > 0.0 && beforeSize > postSize) {
                ceil(oneTimeCost / savedPerTurn).toInt()
            } else {
                null
            }
            CompactionPaybackSummary(
                archiveId = compact.archiveId,
                tokensBefore = compact.tokensBefore,
                compactionInputTokens = compact.compactionInput,
                compactionCachedTokens = compact.compactionCached,
                compactionCacheWriteTokens = compact.compactionCacheWrite,
                compactionOutputTokens = compact.compactionOutput,
                postCompactionContextTokens = compact.postContextTokens,
                turnsAfter = compact.turnsAfter,
                paybackTurns = paybackTurns,
                paidBack = paybackTurns != null && compact.turnsAfter >= paybackTurns,
            )
        }

        return CacheDiagnosticsReport(
            attribution = CacheAttributionSummary(
                totalInputTokens = totalInput,
                cachedTokens = totalCached,
                newContentTokens = totalNewContent,
                compactionRepayTokens = totalCompRepay,
                ttlOrPrefixMissTokens = totalTtlMiss,
                identityValid = identityValid &&
                    (totalInput == totalCached + totalNewContent + totalCompRepay + totalTtlMiss),
            ),
            lastCompactionPayback = lastPayback,
        )
    }
}
