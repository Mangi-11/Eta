package io.github.mangi.eta.agent.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.motion.couiPressMotion
import com.itos.coui.motion.rememberCouiPressMotion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text

private val assistantSuggestions = listOf(
    "总结屏幕内容",
    "解释当前页面",
    "提取关键信息",
)

private val suggestionEnterEasing = CubicBezierEasing(0.20f, 0f, 0f, 1f)

@Composable
internal fun EtaAssistantSuggestions(
    onSuggestionClick: (String) -> Unit,
    colors: EtaVoicePanelColors,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = EnterTransition.None,
        exit = shrinkVertically(
            shrinkTowards = Alignment.Bottom,
            animationSpec = tween(160, easing = FastOutSlowInEasing),
        ) + fadeOut(tween(120)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                // 阴影与按压回弹留在横向滚动裁剪框内部，避免上下边缘被水平视口截平。
                .padding(top = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(10.dp))
            assistantSuggestions.forEachIndexed { index, suggestion ->
                val progress = remember(suggestion) { Animatable(0f) }
                val opacity = remember(suggestion) { Animatable(0f) }
                val interactionSource = remember(suggestion) { MutableInteractionSource() }
                val press = rememberCouiPressMotion()
                LaunchedEffect(visible) {
                    if (visible) {
                        progress.snapTo(0f)
                        opacity.snapTo(0f)
                        if (index > 0) delay(index * 42L)
                        coroutineScope {
                            launch {
                                progress.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(250, easing = suggestionEnterEasing),
                                )
                            }
                            launch {
                                opacity.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
                            }
                        }
                    } else {
                        coroutineScope {
                            launch { progress.animateTo(0f, tween(120)) }
                            launch { opacity.animateTo(0f, tween(110)) }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .graphicsLayer {
                            val p = progress.value
                            alpha = opacity.value
                            translationY = (1f - p) * 10.dp.toPx()
                            val entryScale = 0.93f + 0.07f * p
                            scaleX = entryScale
                            scaleY = entryScale
                        }
                        .couiPressMotion(press, enabled = visible, cancelOnConsumedMove = true)
                        .couiAssistantCapsule(
                            surfaceColor = colors.chipBackground,
                            strokeColor = colors.chipStroke,
                            dark = colors.dark,
                            elevation = AssistantCapsuleElevation.Chip,
                            interactive = visible,
                            interactionSource = interactionSource,
                            pressMotion = press,
                            spotlightEnabled = true,
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            enabled = visible,
                            role = Role.Button,
                        ) { onSuggestionClick(suggestion) }
                        .padding(horizontal = 15.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = suggestion,
                        color = colors.inputPrimary.copy(alpha = 0.88f),
                        fontSize = 13.5.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
        }
    }
}
