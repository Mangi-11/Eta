package io.github.mangi.eta.agent.display

import android.graphics.Bitmap
import android.graphics.Point
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import android.view.Display
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Drain the output surface without encoding until a viewer or observation needs pixels. */
internal class VirtualScreenFrameCapture(
    private val reader: ImageReader,
    private val handler: Handler,
    private val display: () -> Display?,
) : Closeable {
    data class Frame(
        val jpeg: ByteArray,
        val id: Long,
        val fingerprint: Long,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val requestId: Long,
    )

    private val lock = Any()
    private val policy = VirtualScreenFrameCapturePolicy()
    private var closed = false
    private var frame: Frame? = null
    // These fields belong exclusively to the image handler. Keep one raw buffer so a
    // static app remains observable even when it does not submit another surface frame.
    private var latestImage: Image? = null
    private var latestRotation = 0
    private var imageVersion = 0L
    private var encodedImageVersion = -1L
    private var scheduled = false
    private val retry = Runnable {
        scheduled = false
        captureLatest()
    }

    init {
        reader.setOnImageAvailableListener({ captureLatest() }, handler)
    }

    fun setViewerVisible(visible: Boolean) {
        synchronized(lock) {
            policy.setViewerVisible(visible, SystemClock.elapsedRealtime())
            if (!visible) frame = null
        }
        if (visible) handler.post { captureLatest() }
    }

    fun invalidate() {
        synchronized(lock) {
            policy.invalidate()
            frame = null
        }
        val released = CountDownLatch(1)
        handler.post {
            latestImage?.close()
            latestImage = null
            released.countDown()
        }
        check(released.await(1, TimeUnit.SECONDS)) { "DISPLAY_FRAME_PENDING" }
    }

    fun currentFrame(): Frame? {
        val ready = synchronized(lock) { frame } ?: return null
        val current = display() ?: return null
        val size = Point().also { current.getRealSize(it) }
        return ready.takeIf { it.width == size.x && it.height == size.y && it.rotation == current.rotation }
    }

    /** A fresh encoding acknowledges this request; a stale JPEG cannot complete it. */
    fun capture(timeoutMs: Long = 1_000L): Frame? {
        val request = synchronized(lock) {
            if (closed) return null
            policy.requestFrame(SystemClock.elapsedRealtime(), timeoutMs)
        }
        handler.post { captureLatest() }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            currentFrame()?.takeIf { it.requestId >= request }?.let { return it }
            SystemClock.sleep(10)
        } while (SystemClock.elapsedRealtime() < deadline)
        return null
    }

    fun describe(): JSONObject = synchronized(lock) {
        JSONObject().put("max_fps", 1_000L / VirtualScreenFrameCapturePolicy.FRAME_INTERVAL_MS)
            .put("viewer_visible", policy.isViewerVisible(SystemClock.elapsedRealtime()))
            .put("encoded_frames", policy.encodedFrames)
    }

    private fun captureLatest() {
        if (synchronized(lock) { closed }) return
        reader.acquireLatestImage()?.let { image ->
            latestImage?.close()
            latestImage = image
            latestRotation = display()?.rotation ?: 0
            imageVersion++
        }
        val image = latestImage ?: return
        if (display()?.rotation != latestRotation) return
        val now = SystemClock.elapsedRealtime()
        val delay = synchronized(lock) {
            if (imageVersion == encodedImageVersion && !policy.hasPendingRequest(now)) return
            policy.delayUntilCapture(now)
        } ?: return
        if (delay > 0) {
            if (!scheduled) {
                scheduled = true
                handler.postDelayed(retry, delay)
            }
            return
        }
        val capture = synchronized(lock) { policy.beginCapture(now) } ?: return
        val plane = image.planes.firstOrNull() ?: return
        if (plane.pixelStride != 4) return
        val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
        var cropped: Bitmap? = null
        var upright: Bitmap? = null
        try {
            padded.copyPixelsFromBuffer(plane.buffer.duplicate())
            cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            upright = VirtualScreenFrameTransform.upright(cropped, latestRotation)
            val encoded = ByteArrayOutputStream()
            if (!upright.compress(Bitmap.CompressFormat.JPEG, 75, encoded)) return
            val jpeg = encoded.toByteArray()
            if (jpeg.size > 2_000_000) return
            val completed = SystemClock.elapsedRealtime()
            val ready = Frame(jpeg, completed, VirtualScreenImageFingerprint.of(upright),
                upright.width, upright.height, latestRotation, capture.requestId)
            synchronized(lock) {
                if (!closed && policy.completeCapture(capture, completed)) {
                    frame = ready
                    encodedImageVersion = imageVersion
                }
            }
        } finally {
            if (upright != null && upright !== cropped) upright.recycle()
            if (cropped != null && cropped !== padded) cropped.recycle()
            padded.recycle()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            policy.invalidate()
            frame = null
        }
        val released = CountDownLatch(1)
        handler.post {
            handler.removeCallbacks(retry)
            reader.setOnImageAvailableListener(null, null)
            latestImage?.close()
            latestImage = null
            released.countDown()
        }
        released.await(1, TimeUnit.SECONDS)
    }
}
