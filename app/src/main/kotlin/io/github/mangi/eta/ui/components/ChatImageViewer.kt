package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import kotlin.math.abs
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Stable
internal class ChatImagePreviewer {
    internal var request by mutableStateOf<ChatImagePreviewRequest?>(null)
        private set
    internal var targetOpen by mutableStateOf(false)
        private set
    internal var activePage by mutableIntStateOf(0)
    private var sessionId by mutableIntStateOf(0)

    fun open(
        images: List<String>,
        initialIndex: Int = 0,
        groupKey: Any? = null,
        sourceBoundsProvider: ((Int) -> Rect?)? = null,
        sourceCornerRadius: Dp = 16.dp,
    ) {
        if (images.isEmpty()) return
        val safeIndex = initialIndex.coerceIn(0, images.lastIndex)
        sessionId += 1
        activePage = safeIndex
        request = ChatImagePreviewRequest(
            id = sessionId,
            images = images.toList(),
            initialIndex = safeIndex,
            groupKey = groupKey,
            sourceBoundsProvider = sourceBoundsProvider,
            sourceCornerRadius = sourceCornerRadius,
        )
        targetOpen = true
    }

    fun dismiss() {
        targetOpen = false
    }

    /** 绘制阶段读取：当前正在飞行或全屏展示的缩略图隐藏原位，避免开合过渡出现重影。 */
    fun isPresenting(groupKey: Any?, index: Int): Boolean {
        val current = request ?: return false
        return groupKey != null && current.groupKey == groupKey && activePage == index
    }

    internal fun clearIfClosed(id: Int) {
        if (!targetOpen && request?.id == id) {
            request = null
        }
    }
}

internal class ChatImagePreviewRequest(
    val id: Int,
    val images: List<String>,
    val initialIndex: Int,
    val groupKey: Any?,
    val sourceBoundsProvider: ((Int) -> Rect?)?,
    val sourceCornerRadius: Dp,
)

internal val LocalChatImagePreviewer = compositionLocalOf<ChatImagePreviewer?> { null }

/**
 * 壳层顶部的全屏图片查看器宿主。
 *
 * 采用单 Animatable 驱动的原位共享边界过渡：从缩略图的真实窗口矩形（Crop 裁剪）平滑展开到全屏居中（Fit），
 * 全程只在绘制阶段读取动画进度，不创建 Dialog 子窗口、不改动系统栏触发整树重排，保证 120Hz 满帧过渡。
 */
@Composable
internal fun ChatImagePreviewHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val previewer = remember { ChatImagePreviewer() }
    var hostWindowOffset by remember { mutableStateOf(Offset.Zero) }
    CompositionLocalProvider(LocalChatImagePreviewer provides previewer) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    if (coords.isAttached) {
                        hostWindowOffset = coords.positionInWindow()
                    }
                },
        ) {
            content()
            val activeRequest = previewer.request
            if (activeRequest != null) {
                key(activeRequest.id) {
                    ChatImageViewerOverlay(
                        request = activeRequest,
                        previewer = previewer,
                        hostWindowOffset = hostWindowOffset,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatImageViewerOverlay(
    request: ChatImagePreviewRequest,
    previewer: ChatImagePreviewer,
    hostWindowOffset: Offset,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val progress = remember { Animatable(0f) }
    val targetOpen = previewer.targetOpen
    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = targetOpen,
        onBackCompleted = previewer::dismiss,
    )

    LaunchedEffect(targetOpen) {
        if (targetOpen) {
            keyboard?.hide()
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = OPEN_SPRING_STIFFNESS,
                ),
            )
        } else {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = CLOSE_SPRING_STIFFNESS,
                ),
            )
            previewer.clearIfClosed(request.id)
        }
    }

    var zoomedPage by remember { mutableIntStateOf(-1) }
    val sourceCornerPx = with(LocalDensity.current) { request.sourceCornerRadius.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Color.Black.copy(alpha = 0.94f * progress.value.coerceIn(0f, 1f)))
            },
    ) {
        if (request.images.size == 1) {
            val bitmap = rememberDataUrlBitmap(request.images[0])
            val sourceRect = request.sourceBoundsProvider?.invoke(0)?.translate(
                translateX = -hostWindowOffset.x,
                translateY = -hostWindowOffset.y,
            )
            HeroZoomableImageCanvas(
                bitmap = bitmap,
                progress = { progress.value },
                closing = !targetOpen,
                sourceRect = sourceRect,
                sourceCornerPx = sourceCornerPx,
                isActivePage = true,
                onZoomedChange = {},
                onDismiss = previewer::dismiss,
            )
        } else {
            val pagerState = rememberPagerState(
                initialPage = request.initialIndex,
                pageCount = { request.images.size },
            )
            LaunchedEffect(pagerState.currentPage) {
                previewer.activePage = pagerState.currentPage
            }
            val pagerScrollEnabled by remember {
                derivedStateOf { zoomedPage != pagerState.currentPage && targetOpen && progress.value > 0.96f }
            }
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 0,
                pageSpacing = 16.dp,
                userScrollEnabled = pagerScrollEnabled,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val bitmap = rememberDataUrlBitmap(request.images[page])
                val sourceRect = if (pagerState.currentPage == page) {
                    request.sourceBoundsProvider?.invoke(page)?.translate(
                        translateX = -hostWindowOffset.x,
                        translateY = -hostWindowOffset.y,
                    )
                } else {
                    null
                }
                HeroZoomableImageCanvas(
                    bitmap = bitmap,
                    progress = { progress.value },
                    closing = !targetOpen,
                    sourceRect = sourceRect,
                    sourceCornerPx = sourceCornerPx,
                    isActivePage = pagerState.settledPage == page,
                    onZoomedChange = { zoomed ->
                        if (zoomed) {
                            zoomedPage = page
                        } else if (zoomedPage == page) {
                            zoomedPage = -1
                        }
                    },
                    onDismiss = previewer::dismiss,
                )
            }
        }

        if (request.images.size > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        alpha = ((progress.value - 0.45f) / 0.55f).coerceIn(0f, 1f)
                    }
                    .statusBarsPadding()
                    .padding(top = 12.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "${previewer.activePage + 1} / ${request.images.size}",
                    style = MiuixTheme.textStyles.footnote1.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = Color.White.copy(alpha = 0.94f),
                )
            }
        }
    }
}

@Composable
private fun HeroZoomableImageCanvas(
    bitmap: ImageBitmap?,
    progress: () -> Float,
    closing: Boolean,
    sourceRect: Rect?,
    sourceCornerPx: Float,
    isActivePage: Boolean,
    onZoomedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val clipPath = remember { Path() }
    val imageDescription = stringResource(R.string.chat_image_attachment)
    val isZoomed by remember { derivedStateOf { scale.value > 1.02f } }

    LaunchedEffect(isZoomed) {
        onZoomedChange(isZoomed)
    }

    LaunchedEffect(isActivePage) {
        if (!isActivePage && scale.value != 1f) {
            scale.snapTo(1f)
            offsetX.snapTo(0f)
            offsetY.snapTo(0f)
        }
    }

    LaunchedEffect(closing) {
        if (closing && (scale.value != 1f || offsetX.value != 0f || offsetY.value != 0f)) {
            val closeSpec = spring<Float>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = CLOSE_SPRING_STIFFNESS,
            )
            launch { scale.animateTo(1f, closeSpec) }
            launch { offsetX.animateTo(0f, closeSpec) }
            launch { offsetY.animateTo(0f, closeSpec) }
        }
    }

    if (bitmap == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        return
    }

    val bmpW = bitmap.width.coerceAtLeast(1)
    val bmpH = bitmap.height.coerceAtLeast(1)
    val imageAspect = bmpW.toFloat() / bmpH.toFloat()
    val zoomSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = ZOOM_SPRING_STIFFNESS,
    )

    fun fittedDimensions(viewW: Float, viewH: Float): Pair<Float, Float> {
        val viewAspect = viewW / viewH.coerceAtLeast(1f)
        return if (imageAspect > viewAspect) {
            viewW to (viewW / imageAspect)
        } else {
            (viewH * imageAspect) to viewH
        }
    }

    fun maxPan(viewW: Float, viewH: Float, targetScale: Float): Pair<Float, Float> {
        val (fitW, fitH) = fittedDimensions(viewW, viewH)
        val maxX = ((fitW * targetScale - viewW) / 2f).coerceAtLeast(0f)
        val maxY = ((fitH * targetScale - viewH) / 2f).coerceAtLeast(0f)
        return maxX to maxY
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = imageDescription }
            .pointerInput(bitmap) {
                detectTapGestures(
                    onTap = { onDismiss() },
                    onDoubleTap = { tapOffset ->
                        val viewW = size.width.toFloat().coerceAtLeast(1f)
                        val viewH = size.height.toFloat().coerceAtLeast(1f)
                        scope.launch {
                            if (scale.value > 1.05f) {
                                launch { scale.animateTo(1f, zoomSpring) }
                                launch { offsetX.animateTo(0f, zoomSpring) }
                                launch { offsetY.animateTo(0f, zoomSpring) }
                            } else {
                                val targetScale = DOUBLE_TAP_ZOOM_SCALE
                                val (maxX, maxY) = maxPan(viewW, viewH, targetScale)
                                val targetX = ((viewW / 2f - tapOffset.x) * (targetScale - 1f))
                                    .coerceIn(-maxX, maxX)
                                val targetY = ((viewH / 2f - tapOffset.y) * (targetScale - 1f))
                                    .coerceIn(-maxY, maxY)
                                launch { scale.animateTo(targetScale, zoomSpring) }
                                launch { offsetX.animateTo(targetX, zoomSpring) }
                                launch { offsetY.animateTo(targetY, zoomSpring) }
                            }
                        }
                    },
                )
            }
            .pointerInput(bitmap) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val viewW = size.width.toFloat().coerceAtLeast(1f)
                    val viewH = size.height.toFloat().coerceAtLeast(1f)
                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }
                        val currentlyZoomed = scale.value > 1.02f
                        if (pointerCount >= 2 || currentlyZoomed) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (zoomChange != 1f || panChange != Offset.Zero) {
                                val nextScale = (scale.value * zoomChange).coerceIn(MIN_PINCH_SCALE, MAX_ZOOM_SCALE)
                                val (maxX, maxY) = maxPan(viewW, viewH, nextScale.coerceAtLeast(1f))
                                val nextX = if (nextScale <= 1f) {
                                    0f
                                } else {
                                    (offsetX.value + panChange.x).coerceIn(-maxX, maxX)
                                }
                                val nextY = if (nextScale <= 1f) {
                                    0f
                                } else {
                                    (offsetY.value + panChange.y).coerceIn(-maxY, maxY)
                                }
                                val canConsume = pointerCount >= 2 ||
                                    (panChange.x != 0f && nextX != offsetX.value) ||
                                    abs(panChange.y) > abs(panChange.x)
                                scope.launch {
                                    scale.snapTo(nextScale)
                                    offsetX.snapTo(nextX)
                                    offsetY.snapTo(nextY)
                                }
                                if (canConsume) {
                                    event.changes.forEach { change ->
                                        if (change.positionChanged()) change.consume()
                                    }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (scale.value < 1f) {
                        scope.launch {
                            launch { scale.animateTo(1f, zoomSpring) }
                            launch { offsetX.animateTo(0f, zoomSpring) }
                            launch { offsetY.animateTo(0f, zoomSpring) }
                        }
                    }
                }
            },
    ) {
        val viewW = size.width
        val viewH = size.height
        if (viewW <= 0f || viewH <= 0f) return@Canvas

        val p = progress().coerceIn(0f, 1f)
        val (fitW, fitH) = fittedDimensions(viewW, viewH)
        val targetLeft = (viewW - fitW) / 2f
        val targetTop = (viewH - fitH) / 2f
        val targetRight = targetLeft + fitW
        val targetBottom = targetTop + fitH

        val src = sourceRect?.takeIf { it.width > 1f && it.height > 1f }
        if (src != null && p < 0.999f) {
            // 从缩略图的 Crop 矩形连续插值到全屏 Fit 矩形，亚像素浮点矩阵变换无抖动。
            val cropScale = maxOf(src.width / fitW, src.height / fitH)
            val currentScale = lerp(cropScale, 1f, p) * scale.value
            val centerX = lerp(src.center.x, viewW / 2f, p) + offsetX.value * p
            val centerY = lerp(src.center.y, viewH / 2f, p) + offsetY.value * p
            val clipLeft = lerp(src.left, targetLeft, p)
            val clipTop = lerp(src.top, targetTop, p)
            val clipRight = lerp(src.right, targetRight, p)
            val clipBottom = lerp(src.bottom, targetBottom, p)
            val corner = lerp(sourceCornerPx, 0f, p)

            clipPath.reset()
            clipPath.addRoundRect(
                RoundRect(
                    left = clipLeft,
                    top = clipTop,
                    right = clipRight,
                    bottom = clipBottom,
                    cornerRadius = CornerRadius(corner, corner),
                ),
            )
            val totalScaleX = (fitW / bmpW.toFloat()) * currentScale
            val totalScaleY = (fitH / bmpH.toFloat()) * currentScale
            clipPath(clipPath) {
                withTransform({
                    translate(
                        left = centerX - (bmpW * totalScaleX) / 2f,
                        top = centerY - (bmpH * totalScaleY) / 2f,
                    )
                    scale(scaleX = totalScaleX, scaleY = totalScaleY, pivot = Offset.Zero)
                }) {
                    drawImage(
                        image = bitmap,
                        dstSize = IntSize(bmpW, bmpH),
                        filterQuality = FilterQuality.High,
                    )
                }
            }
        } else {
            val fallbackAlpha = if (src == null) p else 1f
            val fallbackScale = if (src == null) lerp(0.94f, 1f, p) else 1f
            val totalScaleX = (fitW / bmpW.toFloat()) * fallbackScale * scale.value
            val totalScaleY = (fitH / bmpH.toFloat()) * fallbackScale * scale.value
            val centerX = viewW / 2f + offsetX.value
            val centerY = viewH / 2f + offsetY.value
            withTransform({
                translate(
                    left = centerX - (bmpW * totalScaleX) / 2f,
                    top = centerY - (bmpH * totalScaleY) / 2f,
                )
                scale(scaleX = totalScaleX, scaleY = totalScaleY, pivot = Offset.Zero)
            }) {
                drawImage(
                    image = bitmap,
                    dstSize = IntSize(bmpW, bmpH),
                    alpha = fallbackAlpha,
                    filterQuality = FilterQuality.High,
                )
            }
        }
    }
}

private const val OPEN_SPRING_STIFFNESS = 580f
private const val CLOSE_SPRING_STIFFNESS = 680f
private const val ZOOM_SPRING_STIFFNESS = 520f
private const val DOUBLE_TAP_ZOOM_SCALE = 2.5f
private const val MIN_PINCH_SCALE = 0.85f
private const val MAX_ZOOM_SCALE = 4f
