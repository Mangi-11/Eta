package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.roleplay.CharacterProfile
import io.github.mangi.eta.ui.components.CharacterAvatar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 角色卡竖版比例与原版角色卡图一致（2:3），裁剪时保留人物上半身。 */
internal const val CharacterPortraitAspect = 2f / 3f
internal val CharacterPortraitCorner = 20.dp

/**
 * 角色库卡片：整张图铺满，底部渐暗承载名字与标签，避免图文分栏显得像设置项。
 * 收藏星标悬浮在右上角，点击不进入详情。
 */
@Composable
internal fun CharacterPortraitCard(
    profile: CharacterProfile,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val card = profile.card
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(CharacterPortraitAspect)
            .squircleClip(CharacterPortraitCorner)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
    ) {
        CharacterAvatar(
            name = card.name,
            avatarPath = profile.avatarPath,
            size = maxWidth.coerceAtLeast(maxHeight),
            shape = RectangleShape,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggleFavorite),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color.Black.copy(alpha = 0.28f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (card.favorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                    contentDescription = stringResource(
                        if (card.favorite) R.string.character_action_unfavorite else R.string.character_action_favorite,
                    ),
                    modifier = Modifier.size(16.dp),
                    tint = if (card.favorite) FavoriteColor else Color.White,
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = card.name,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (card.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    card.tags.take(2).forEach { tag -> PortraitTag(tag) }
                }
            }
        }
    }
}

@Composable
private fun PortraitTag(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = Color.White.copy(alpha = 0.92f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 收藏星标的暖金色，固定值保证落在任意头像上都可辨认。 */
internal val FavoriteColor = Color(0xFFFFC94D)

/**
 * 网格末尾的新建卡：与角色卡同尺寸的描边占位，上半区新建、下方导入两个入口，
 * 视觉上弱于真实角色，不抢主内容。
 */
@Composable
internal fun CharacterAddTile(
    enabled: Boolean,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .aspectRatio(CharacterPortraitAspect)
            .squircleClip(CharacterPortraitCorner)
            .background(colors.surfaceContainer)
            .squircleBorder(0.5.dp, colors.outline.copy(alpha = 0.5f), CharacterPortraitCorner),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onCreate),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(colors.primary.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(24.dp), tint = colors.primary)
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.character_action_new), style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Medium, color = colors.onSurface)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.outline.copy(alpha = 0.4f)),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onImport)
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.onSurfaceVariantSummary)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.character_action_import_card), style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
        }
    }
}
