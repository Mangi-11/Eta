package fuck.andes.agent.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentImageCodecTest {
    @Test
    fun screenCopyUsesFullResolutionLosslessWebp() {
        val bitmap = patternedBitmap(width = 1_200, height = 2_400)
        try {
            val image = AgentImageCodec.fromScreenBitmap(bitmap, source = "screen")
            val width = image.width ?: error("缺少图片宽度")
            val height = image.height ?: error("缺少图片高度")
            val decoded = BitmapFactory.decodeByteArray(
                image.reference.decodeDataUrl(),
                0,
                image.bytes,
            ) ?: error("无法解码模型截图")

            assertEquals("image/webp", image.mimeType)
            assertTrue(image.reference.startsWith("data:image/webp;base64,"))
            assertEquals(bitmap.width, width)
            assertEquals(bitmap.height, height)
            assertTrue(bitmap.sameAs(decoded))
            decoded.recycle()
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun encodedScreenBytesNeverLosePixelsOrDimensions() {
        val bitmap = patternedBitmap(width = 900, height = 1_800)
        val png = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        try {
            val image = AgentImageCodec.fromScreenBytes(png, source = "screen")
            val encoded = image.reference.decodeDataUrl()
            val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
                ?: error("无法解码模型截图")

            assertEquals(bitmap.width, image.width)
            assertEquals(bitmap.height, image.height)
            assertTrue(bitmap.sameAs(decoded))
            decoded.recycle()
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun largeAttachmentKeepsOriginalBytesAndDimensions() {
        val bitmap = patternedBitmap(width = 2_400, height = 1_600)
        val original = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()

        val image = AgentImageCodec.fromAttachmentBytes(
            bytes = original,
            source = "user_attach",
            mimeHint = "image/jpeg",
        )
        val width = image.width ?: error("缺少图片宽度")
        val height = image.height ?: error("缺少图片高度")

        assertEquals("image/jpeg", image.mimeType)
        assertEquals(2_400, width)
        assertEquals(1_600, height)
        assertEquals(original.size, image.bytes)
        assertArrayEquals(original, image.reference.decodeDataUrl())
    }

    @Test
    fun smallAttachmentKeepsItsOriginalEncoding() {
        val bitmap = patternedBitmap(width = 96, height = 96)
        val original = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()

        val image = AgentImageCodec.fromAttachmentBytes(
            bytes = original,
            source = "user_attach",
            mimeHint = "image/png",
        )

        assertEquals("image/png", image.mimeType)
        assertEquals(original.size, image.bytes)
        assertEquals(96, image.width)
        assertEquals(96, image.height)
    }

    @Test
    fun chatPreviewIsIndependentFromTheOriginalFile() {
        val context = RuntimeEnvironment.getApplication()
        val sourceFile = File(context.cacheDir, "image-preview-${System.nanoTime()}.jpg")
        val bitmap = patternedBitmap(width = 1_200, height = 800)
        FileOutputStream(sourceFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
        }
        bitmap.recycle()
        val originalSize = sourceFile.length()
        try {
            val source = AgentImageCodec.fromTransferReference(
                context = context,
                value = sourceFile.absolutePath,
                source = "user_attach",
            ) ?: error("无法读取测试图片")
            val preview = AgentImageCodec.previewFromReference(context, source)
                ?: error("无法生成测试预览")

            assertEquals(sourceFile.absolutePath, source.reference)
            assertEquals("image/jpeg", preview.mimeType)
            assertTrue(maxOf(preview.width!!, preview.height!!) <= 512)
            assertEquals(originalSize, sourceFile.length())
        } finally {
            sourceFile.delete()
        }
    }

    private fun patternedBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(32, 92, 180)
                strokeWidth = 7f
            }
            val step = (minOf(width, height) / 12).coerceAtLeast(8)
            for (offset in 0 until maxOf(width, height) step step) {
                canvas.drawLine(0f, offset.toFloat(), width.toFloat(), (offset / 2).toFloat(), paint)
                canvas.drawLine(offset.toFloat(), 0f, (offset / 2).toFloat(), height.toFloat(), paint)
            }
        }

    private fun String.decodeDataUrl(): ByteArray =
        Base64.decode(substringAfter("base64,"), Base64.DEFAULT)
}
