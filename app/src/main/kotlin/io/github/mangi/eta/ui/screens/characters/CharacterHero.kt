package io.github.mangi.eta.ui.screens.characters

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.roleplay.CharacterMacros
import io.github.mangi.eta.agent.roleplay.CharacterProfile
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.CharacterAvatar
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.rememberCharacterImage
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val HeroAvatarWidth = 132.dp
private val HeroAvatarHeight = HeroAvatarWidth / CharacterPortraitAspect

/**
 * 角色详情主视觉：头像本身放大模糊作为氛围底色并渐隐到页面背景，前景是竖版头像与名字。
 * 模糊只作用在一张已降采样的图上，绘制成本固定，不随页面内容变化。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CharacterHero(
    profile: CharacterProfile,
    busy: Boolean,
    onChangeAvatar: () -> Unit,
    onToggleFavorite: () -> Unit,
    onStart: () -> Unit,
    onReadDescription: () -> Unit,
    userName: String,
) {
    val card = profile.card
    val description = remember(card, userName) { CharacterMacros.expand(card.description, card, userName) }
    val colors = MiuixTheme.colorScheme
    val image = rememberCharacterImage(profile.avatarPath, HeroAvatarHeight)
    Box(modifier = Modifier.fillMaxWidth()) {
        if (image != null) {
            CharacterAvatar(
                name = card.name,
                avatarPath = profile.avatarPath,
                size = HeroAvatarHeight,
                shape = RectangleShape,
                image = image,
                modifier = Modifier
                    .matchParentSize()
                    .blur(56.dp)
                    .alpha(0.4f),
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    // 上下两端都融回页面底色：顶部不在顶栏下切出硬边，底部自然接到下方分组。
                    Brush.verticalGradient(
                        0f to colors.background,
                        0.18f to colors.background.copy(alpha = 0f),
                        0.6f to colors.background.copy(alpha = 0.2f),
                        1f to colors.background,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CharacterCardPadding)
                .padding(top = 12.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                CharacterAvatar(
                    name = card.name,
                    avatarPath = profile.avatarPath,
                    size = HeroAvatarHeight,
                    shape = RectangleShape,
                    image = image,
                    modifier = Modifier
                        .width(HeroAvatarWidth)
                        .height(HeroAvatarHeight)
                        .squircleClip(CharacterPortraitCorner),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 10.dp, y = 10.dp)
                        .size(36.dp)
                        .squircleSurface(colors.surface, 18.dp)
                        .squircleBorder(0.5.dp, colors.outline.copy(alpha = 0.5f), 18.dp)
                        .clickable(enabled = !busy, onClick = onChangeAvatar),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoCamera,
                        contentDescription = "更换头像",
                        modifier = Modifier.size(18.dp),
                        tint = colors.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.name,
                    style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false),
                )
                IconButton(onClick = onToggleFavorite, enabled = !busy, minWidth = 36.dp, minHeight = 36.dp) {
                    Icon(
                        imageVector = if (card.favorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                        contentDescription = if (card.favorite) "取消收藏" else "收藏",
                        modifier = Modifier.size(20.dp),
                        tint = if (card.favorite) FavoriteColor else colors.onSurfaceVariantSummary,
                    )
                }
            }
            val byline = listOfNotNull(
                card.creator.takeIf(String::isNotBlank)?.let { "作者 $it" },
                card.version.takeIf(String::isNotBlank)?.let { "v$it" },
            ).joinToString(" · ")
            if (byline.isNotEmpty()) {
                Text(
                    text = byline,
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (card.tags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    maxLines = 2,
                ) {
                    card.tags.forEach { tag ->
                        Text(
                            text = tag,
                            style = MiuixTheme.textStyles.footnote2,
                            color = colors.onSurfaceVariantSummary,
                            maxLines = 1,
                            modifier = Modifier
                                .squircleSurface(colors.surfaceContainerHigh, 10.dp)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            if (description.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = description,
                    style = MiuixTheme.textStyles.body2,
                    color = colors.onSurface.copy(alpha = 0.86f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .squircleClip(12.dp)
                        .clickable(onClick = onReadDescription)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                CouiButton(
                    text = "开始对话",
                    enabled = !busy,
                    onClick = onStart,
                    role = CouiButtonRole.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                TextButton(
                    text = "开始对话",
                    enabled = !busy,
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/**
 * 开场白横向翻页：每张卡完整预览一段开场，当前页即选中的开场白，免去单选列表的二次确认。
 * 卡片定高并截断，全文通过“阅读全文”弹窗查看，翻页时高度不跳动。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GreetingPager(
    greetings: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onRead: (Int) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val pagerState = rememberPagerState(initialPage = selected.coerceIn(0, greetings.lastIndex)) { greetings.size }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect(onSelect)
    }
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = CharacterCardPadding),
            pageSpacing = 10.dp,
        ) { page ->
            val text = greetings[page]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GreetingCardHeight)
                    .squircleSurface(colors.surfaceContainer, 20.dp)
                    .clickable(enabled = text.isNotBlank()) { onRead(page) }
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = greetingTitle(page),
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Medium,
                        color = colors.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (greetings.size > 1) {
                        val badge by animateColorAsState(
                            if (page == pagerState.settledPage) colors.primary else Color.Transparent,
                            label = "greeting_selected",
                        )
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .background(badge, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (page == pagerState.settledPage) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "已选择",
                                    modifier = Modifier.size(12.dp),
                                    tint = colors.onPrimary,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = text.ifBlank { "没有预设开场白，由你先开口" },
                    style = MiuixTheme.textStyles.body2,
                    color = if (text.isBlank()) colors.onSurfaceVariantSummary else colors.onSurface,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (text.isNotBlank()) {
                    Text(
                        text = "阅读全文",
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.primary,
                    )
                }
            }
        }
        if (greetings.size > 1) {
            PagerDots(count = greetings.size, current = pagerState.currentPage)
        }
    }
}

@Composable
private fun PagerDots(count: Int, current: Int) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count.coerceAtMost(MaxDots)) { index ->
            val active = index == current.coerceAtMost(MaxDots - 1)
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(if (active) 16.dp else 6.dp)
                    .background(
                        if (active) colors.primary else colors.onSurfaceVariantSummary.copy(alpha = 0.3f),
                        CircleShape,
                    ),
            )
        }
    }
}

private val GreetingCardHeight = 200.dp
private const val MaxDots = 9
