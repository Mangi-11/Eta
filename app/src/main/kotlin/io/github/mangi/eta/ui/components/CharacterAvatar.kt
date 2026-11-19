package io.github.mangi.eta.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

/** 角色在对话与列表中的身份信息；头像路径为空时用名字生成字标头像。 */
@Immutable
internal data class RoleplayChatUi(
    val characterName: String,
    val avatarPath: String?,
    val userName: String,
)

/** 当前对话绑定的角色；普通 Agent 对话为 null，消息与空态据此切换为角色样式。 */
internal val LocalRoleplayChat = staticCompositionLocalOf<RoleplayChatUi?> { null }

/**
 * 角色图片解码缓存。角色卡原图常为 1024px 以上的 PNG，按显示尺寸降采样后缓存，
 * 列表滚动与消息重组都只命中内存；键包含目标尺寸，避免小头像复用大图浪费内存。
 */
private object CharacterImageCache {
    private val cache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun peek(path: String, maxPx: Int): ImageBitmap? = cache.get(key(path, maxPx))

    suspend fun load(path: String, maxPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(key(path, maxPx))?.let { return@withContext it }
        val file = File(path)
        if (!file.isFile) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext null
        bitmap.asImageBitmap().also { cache.put(key(path, maxPx), it) }
    }

    // 编辑头像会生成新文件名，同一路径内容不变，文件修改时间纳入键只为防御外部覆盖。
    private fun key(path: String, maxPx: Int) = "$path#${File(path).lastModified()}#$maxPx"
}

/** 按显示尺寸异步解码角色图片；缓存命中时首帧即有图，不闪字标占位。 */
@Composable
internal fun rememberCharacterImage(path: String?, displaySize: Dp): ImageBitmap? {
    val maxPx = with(LocalDensity.current) { displaySize.roundToPx() }.coerceIn(32, 1024)
    val image by produceState(initialValue = path?.let { CharacterImageCache.peek(it, maxPx) }, path, maxPx) {
        value = path?.let { CharacterImageCache.load(it, maxPx) }
    }
    return image
}

/**
 * 角色头像：有图时按裁剪填满，人物脸部通常在画面上方，因此纵向对齐顶部；
 * 无图时用名字首字与稳定配色生成字标，同一角色在所有页面颜色一致。
 */
@Composable
internal fun CharacterAvatar(
    name: String,
    avatarPath: String?,
    size: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
    borderWidth: Dp = 0.dp,
    borderColor: Color = Color.Unspecified,
    image: ImageBitmap? = rememberCharacterImage(avatarPath, size),
) {
    // size 决定解码分辨率与默认正方形尺寸；调用方传 fillMaxSize/matchParentSize/明确宽高时遵循外层约束，
    // 仅附加非尺寸修饰符时仍稳定兜底为 size，避免内部 matchParentSize 把容器压成 0x0。
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .then(
                if (borderWidth > 0.dp && borderColor != Color.Unspecified) {
                    Modifier.border(borderWidth, borderColor, shape)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            CharacterMonogram(name = name, size = size, modifier = Modifier.matchParentSize())
        }
    }
}

@Composable
internal fun CharacterMonogram(name: String, size: Dp, modifier: Modifier = Modifier.size(size)) {
    val gradient = monogramGradient(name)
    Box(
        modifier = modifier.background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

/** 字标配色：低饱和的双色渐变，深浅色主题下白字都清晰；按名字散列取色，结果稳定。 */
private val MonogramPalette = listOf(
    listOf(Color(0xFFF58FA8), Color(0xFFC56CD6)),
    listOf(Color(0xFF7FA7F5), Color(0xFF6A6FE0)),
    listOf(Color(0xFF6CC9B4), Color(0xFF3E9FB8)),
    listOf(Color(0xFFF5B36C), Color(0xFFE07A5F)),
    listOf(Color(0xFFA48BEB), Color(0xFF6C63C9)),
    listOf(Color(0xFF8CC97A), Color(0xFF4FA37C)),
)

internal fun monogramGradient(name: String): List<Color> =
    MonogramPalette[Math.floorMod(name.trim().hashCode(), MonogramPalette.size)]
