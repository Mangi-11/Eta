package io.github.mangi.eta.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CharacterAvatar(
    name: String,
    path: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    bytes: ByteArray? = null,
    revision: Long = 0,
) {
    val bitmap by produceState<ImageBitmap?>(null, path, bytes, revision) {
        value = withContext(Dispatchers.IO) {
            if (bytes == null && path == null) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            else BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            (if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            else BitmapFactory.decodeFile(path, options))?.asImageBitmap()
        }
    }
    Box(
        modifier = modifier.size(size).clip(CircleShape)
            .background(MiuixTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            val placeholderStyle = when {
                size >= 64.dp -> MiuixTheme.textStyles.title2
                size >= 44.dp -> MiuixTheme.textStyles.title3
                else -> MiuixTheme.textStyles.body1
            }
            Text(name.take(1).ifBlank { "角" }, style = placeholderStyle)
        }
    }
}
