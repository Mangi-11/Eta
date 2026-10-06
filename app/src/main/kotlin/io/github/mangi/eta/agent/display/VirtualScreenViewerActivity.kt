package io.github.mangi.eta.agent.display

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaPreferenceTheme
import io.github.mangi.eta.ui.components.MiuixBackButton
import io.github.mangi.eta.ui.layout.WidePageContent
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal class VirtualScreenViewerActivity : ComponentActivity() {
    private var surface: VirtualScreenSurfaceView? = null
    private var hasFrame by mutableStateOf(false)
    private var inputError by mutableStateOf(false)
    private var interactionEnabled by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            val appearance by AppearanceSettingsRepository.settingsFlow()
                .collectAsState(initial = AppearanceSettings())
            AgentAppTheme(appearance, applyInterfaceScale = true) { ViewerScreen() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    VirtualScreenSession.state.collect { state ->
                        if (state.display == null) {
                            surface?.clearFrame(); hasFrame = false
                        }
                        surface?.showGesture(state.gesture)
                    }
                }
                while (true) {
                    val info = VirtualScreenSession.state.value.display
                    if (info != null) {
                        val next = readFrame()
                        if (VirtualScreenSession.state.value.display?.sessionId == info.sessionId && next != null) {
                            surface?.setFrame(next, info) ?: next.recycle()
                            hasFrame = surface != null
                        } else {
                            next?.recycle()
                            surface?.clearFrame()
                            hasFrame = false
                        }
                    }
                    delay(120)
                }
            }
        }
    }

    private suspend fun readFrame(): Bitmap? {
        val pending = AtomicReference<Bitmap?>()
        return try {
            withContext(Dispatchers.IO) {
                val frame = VirtualScreenSession.observeForViewer(this@VirtualScreenViewerActivity)
                frame.images.firstOrNull()?.reference?.substringAfter("base64,")?.let { encoded ->
                    pending.set(runCatching {
                        val bytes = Base64.decode(encoded, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull())
                }
            }
            pending.getAndSet(null)
        } finally {
            // withContext may discard its result on pause; release that decoded frame too.
            pending.getAndSet(null)?.recycle()
        }
    }

    private fun sendInput(sessionId: String, args: JSONObject) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                VirtualScreenSession.inputForViewer(
                    this@VirtualScreenViewerActivity,
                    sessionId,
                    args
                )
            }
            inputError = !JSONObject(result.content).optBoolean("ok")
        }
    }

    @Composable
    private fun ViewerScreen() {
        val state by VirtualScreenSession.state.collectAsState()
        val display = state.display
        // Keep the video viewer's compact bar opaque; live native frames do not need a blur backdrop.
        EtaPreferenceTheme {
            Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
                SmallTopAppBar(
                    title = stringResource(R.string.virtual_screen_viewer_title),
                    navigationIcon = { MiuixBackButton(onClick = ::finish) })
            }) { padding ->
                WidePageContent { sidePadding ->
                    Column(
                        Modifier.fillMaxSize().horizontalCutoutPadding().padding(padding)
                            .padding(horizontal = sidePadding + 16.dp)
                            .navigationBarsPadding(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val statusColor =
                                    if (display != null) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
                                Row(
                                    Modifier.clip(RoundedCornerShape(12.dp))
                                        .background(statusColor.copy(alpha = 0.12f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        Modifier.size(6.dp).clip(CircleShape)
                                            .background(statusColor)
                                    )
                                    Text(
                                        stringResource(if (display != null) R.string.virtual_screen_live else R.string.virtual_screen_idle),
                                        style = MiuixTheme.textStyles.footnote1, color = statusColor
                                    )
                                }
                                if (display != null && state.lastAction.isNotBlank()) {
                                    val action = when (state.lastAction) {
                                        "launch" -> "launch_app"; "back", "key" -> "press_key"; else -> state.lastAction
                                    }
                                    Text(
                                        if (action == "create") stringResource(R.string.virtual_screen_loading) else toolDisplayName(
                                            action
                                        ), style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                            display?.let {
                                Text(
                                    "${it.width} × ${it.height}",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                        Box(
                            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp))
                                .background(Color(0xFF101318))
                        ) {
                            AndroidView(
                                factory = { context ->
                                    VirtualScreenSurfaceView(context).also {
                                        it.onInput = ::sendInput
                                        it.contentDescription =
                                            context.getString(R.string.virtual_screen_touch_description)
                                        surface = it
                                    }
                                },
                                update = { it.touchEnabled = interactionEnabled },
                                modifier = Modifier.fillMaxSize()
                            )
                            if (!hasFrame) {
                                Column(
                                    Modifier.align(Alignment.Center).padding(28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        stringResource(if (display != null) R.string.virtual_screen_loading else R.string.virtual_screen_idle),
                                        color = Color.White,
                                        style = MiuixTheme.textStyles.headline2
                                    )
                                    Text(
                                        stringResource(if (display != null) R.string.virtual_screen_viewer_empty else R.string.virtual_screen_empty_hint),
                                        color = Color(0xFFB7BEC8),
                                        style = MiuixTheme.textStyles.body2
                                    )
                                }
                            }
                        }
                        Text(
                            stringResource(if (inputError) R.string.virtual_screen_input_failed else R.string.virtual_screen_touch_hint),
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (inputError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            EtaTextButton(
                                text = stringResource(R.string.virtual_screen_app_back),
                                enabled = display != null,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    display?.let {
                                        sendInput(
                                            it.sessionId,
                                            JSONObject().put("action", "back")
                                        )
                                    }
                                })
                            EtaTextButton(
                                text = stringResource(if (interactionEnabled) R.string.virtual_screen_touch_on else R.string.virtual_screen_touch_off),
                                modifier = Modifier.weight(1f),
                                onClick = { interactionEnabled = !interactionEnabled })
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        surface?.clearFrame()
        hasFrame = false
        super.onPause()
    }
}
