package io.github.mangi.eta.ui.components.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.components.currentGlassOverlayBackdrop
import io.github.mangi.eta.ui.model.MessageSourceUi
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 回答底部的轻量参考来源胶囊：正文保持纯净不插入角标，点击呼出毛玻璃抽屉查看完整来源。
 */
@Composable
internal fun MessageSourcesPill(
    messageId: String,
    sources: List<MessageSourceUi>,
    modifier: Modifier = Modifier,
) {
    if (sources.isEmpty()) return
    var showSheet by rememberSaveable(messageId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(percent = 50)
    val contentTint = MiuixTheme.colorScheme.onSurfaceVariantSummary

    Row(
        modifier = modifier
            .height(28.dp)
            .clip(shape)
            .background(MiuixTheme.colorScheme.surfaceContainer, shape)
            .clickable { showSheet = true }
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Language,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = contentTint.copy(alpha = 0.78f),
        )
        Text(
            text = stringResource(R.string.chat_sources_pill, sources.size),
            style = MiuixTheme.textStyles.footnote1.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
                fontFeatureSettings = "tnum",
            ),
            color = contentTint,
            maxLines = 1,
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = contentTint.copy(alpha = 0.52f),
        )
    }

    GlassBottomSheet(
        show = showSheet,
        backdrop = currentGlassOverlayBackdrop(),
        progressiveBlur = LocalAppearanceSettings.current.progressiveBlurEnabled,
        title = stringResource(R.string.chat_sources_sheet_title, sources.size),
        onDismissRequest = { showSheet = false },
    ) { innerPadding ->
        MessageSourcesSheetContent(
            sources = sources,
            contentPadding = innerPadding,
        )
    }
}

@Composable
private fun MessageSourcesSheetContent(
    sources: List<MessageSourceUi>,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val maxSheetHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.72f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxSheetHeight)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .navigationBarsPadding()
            .padding(top = 4.dp, bottom = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .squircleSurface(
                    color = MiuixTheme.colorScheme.surfaceContainer,
                    cornerRadius = 16.dp,
                ),
        ) {
            sources.forEachIndexed { index, source ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                        thickness = 0.5.dp,
                        color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.55f),
                    )
                }
                MessageSourceRow(
                    index = index + 1,
                    source = source,
                    onClick = { openSourceUrl(context, source.url) },
                )
            }
        }
    }
}

@Composable
private fun MessageSourceRow(
    index: Int,
    source: MessageSourceUi,
    onClick: () -> Unit,
) {
    val host = sourceDisplayHost(source.url)
    val title = source.title.ifBlank { host }
    val subtitle = host.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index.toString(),
                style = MiuixTheme.textStyles.footnote2.copy(
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                ),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body2.copy(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote1.copy(
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = stringResource(R.string.chat_sources_open_desc),
            modifier = Modifier
                .padding(top = 3.dp)
                .size(15.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f),
        )
    }
}

internal fun sourceDisplayHost(url: String): String {
    val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
    if (host.isNotBlank()) return host.removePrefix("www.")
    return url
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .substringBefore('/')
}

private fun openSourceUrl(context: Context, url: String) {
    val uri = runCatching { Uri.parse(url) }.getOrNull()
    val scheme = uri?.scheme.orEmpty()
    if (uri == null || (!scheme.equals("https", ignoreCase = true) && !scheme.equals("http", ignoreCase = true))) {
        return
    }
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.chat_sources_open_failed), Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, context.getString(R.string.chat_sources_open_failed), Toast.LENGTH_SHORT).show()
    }
}
