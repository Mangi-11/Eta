package io.github.mangi.eta.agent.display

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.mangi.eta.R
import java.util.concurrent.Executors

/** 查看虚拟屏帧；不会把任务搬回主屏，也不将点击转发给应用。 */
internal class VirtualScreenViewerActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var resumed = false
    private var generation = 0L
    private lateinit var image: ImageView
    private lateinit var status: TextView
    private var bitmap: android.graphics.Bitmap? = null
    private val refresh = object : Runnable {
        override fun run() {
            if (!resumed) return
            val epoch = generation
            worker.execute {
                val result = VirtualScreenSession.observeForViewer(this@VirtualScreenViewerActivity)
                val reference = result.images.firstOrNull()?.reference
                val next =
                    reference?.substringAfter("base64,")?.let { Base64.decode(it, Base64.DEFAULT) }
                        ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                handler.post {
                    if (!resumed || generation != epoch) {
                        next?.recycle(); return@post
                    }
                    if (next != null) {
                        image.setImageBitmap(next); bitmap?.recycle(); bitmap =
                            next; status.setText(R.string.virtual_screen_viewer_running)
                    } else status.setText(R.string.virtual_screen_viewer_empty)
                    handler.postDelayed(this, 750)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        status = TextView(this).apply {
            setPadding(
                24,
                48,
                24,
                16
            ); setText(R.string.virtual_screen_viewer_running)
        }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    override fun onResume() {
        super.onResume(); generation++; resumed = true; handler.post(refresh)
    }

    override fun onPause() {
        generation++; resumed = false; handler.removeCallbacks(refresh); super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow(); bitmap?.recycle(); super.onDestroy()
    }
}
