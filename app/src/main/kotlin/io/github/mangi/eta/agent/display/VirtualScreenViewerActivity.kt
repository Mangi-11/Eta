package io.github.mangi.eta.agent.display

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.mangi.eta.ui.components.EtaPreferenceTheme
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixBackButton
import io.github.mangi.eta.ui.layout.WidePageContent
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal class VirtualScreenViewerActivity : ComponentActivity() {
    private var surface: VirtualScreenSurfaceView? = null
    private var hasFrame by mutableStateOf(false)
    private var inputError by mutableStateOf(false)
    private var interactionEnabled by mutableStateOf(true)
    private var showHistory by mutableStateOf(false)
    private var lastFrameId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            val appearance by AppearanceSettingsRepository.settingsFlow().collectAsState(initial = AppearanceSettings())
            AgentAppTheme(appearance, applyInterfaceScale = true) { ViewerScreen() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    VirtualScreenSession.state.collect { state ->
                        if (state.display == null) { surface?.clearFrame(); hasFrame = false; lastFrameId = 0 }
                        surface?.showGesture(state.gesture)
                    }
                }
                while (true) {
                    val info = VirtualScreenSession.state.value.display
                    if (info != null) {
                        val next = readFrame()
                        if (VirtualScreenSession.state.value.display?.sessionId == info.sessionId) {
                            if (next != null) {
                                val view = surface
                                if (view != null) {
                                    view.setFrame(next.bitmap, info)
                                    lastFrameId = next.id
                                    hasFrame = true
                                } else next.bitmap.recycle()
                            }
                        } else {
                            next?.bitmap?.recycle(); surface?.clearFrame(); hasFrame = false; lastFrameId = 0
                        }
                    }
                    delay(32)
                }
            }
        }
    }

    private data class Frame(val bitmap: Bitmap, val id: Long)

    private suspend fun readFrame(): Frame? {
        val pending = AtomicReference<Bitmap?>()
        return try {
            val result = withContext(Dispatchers.IO) {
                val frame = VirtualScreenSession.observeForViewer(this@VirtualScreenViewerActivity, lastFrameId)
                val json = JSONObject(frame.content)
                val bitmap = frame.images.firstOrNull()?.reference?.substringAfter("base64,")?.let { encoded ->
                    runCatching {
                        val bytes = Base64.decode(encoded, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }.getOrNull()
                }
                pending.set(bitmap)
                bitmap?.let { Frame(it, json.optLong("frameId")) }
            }
            pending.set(null)
            result
        } finally {
            pending.getAndSet(null)?.recycle()
        }
    }

    private fun sendInput(sessionId: String, args: JSONObject) {
        inputError = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                VirtualScreenSession.inputForViewer(this@VirtualScreenViewerActivity, sessionId, args)
            }
            inputError = !JSONObject(result.content).optBoolean("ok")
        }
    }

    @Composable
    private fun ViewerScreen() {
        val state by VirtualScreenSession.state.collectAsState()
        val display = state.display
        EtaPreferenceTheme {
            Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
                SmallTopAppBar(
                    title = stringResource(R.string.virtual_screen_viewer_title),
                    navigationIcon = { MiuixBackButton(onClick = ::finish) },
                    actions = {
                        TaskIndicator(state, onClick = { showHistory = true })
                        IconButton(onClick = { interactionEnabled = !interactionEnabled }) {
                            Icon(
                                imageVector = if (interactionEnabled) Icons.Outlined.TouchApp else Icons.Outlined.Block,
                                contentDescription = stringResource(if (interactionEnabled) R.string.virtual_screen_touch_on else R.string.virtual_screen_touch_off),
                                tint = if (interactionEnabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    },
                )
            }) { padding ->
                WidePageContent { sidePadding ->
                    Column(
                        Modifier.fillMaxSize().horizontalCutoutPadding().padding(padding)
                            .padding(horizontal = sidePadding + 16.dp).navigationBarsPadding(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            val aspect = display?.let { it.width.toFloat() / it.height } ?: (9f / 20f)
                            val width = minOf(maxWidth, maxHeight * aspect)
                            Box(Modifier.size(width, width / aspect).clip(RoundedCornerShape(24.dp))
                                .background(Color(0xFF101318)).virtualScreenControlBorder(state.isAgentControlling)) {
                                AndroidView(
                                    factory = { context ->
                                        VirtualScreenSurfaceView(context).also {
                                            it.onInput = ::sendInput
                                            it.contentDescription = context.getString(R.string.virtual_screen_touch_description)
                                            surface = it
                                        }
                                    },
                                    update = { it.touchEnabled = interactionEnabled },
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (!hasFrame) {
                                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(stringResource(if (display != null) R.string.virtual_screen_loading else R.string.virtual_screen_idle),
                                            color = Color.White, style = MiuixTheme.textStyles.headline2)
                                        Text(stringResource(if (display != null) R.string.virtual_screen_viewer_empty else R.string.virtual_screen_empty_hint),
                                            color = Color(0xFFB7BEC8), style = MiuixTheme.textStyles.body2)
                                    }
                                }
                            }
                        }
                        if (inputError) Text(stringResource(R.string.virtual_screen_input_failed),
                            color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            listOf("BACK" to R.string.action_back, "HOME" to R.string.virtual_screen_home, "MENU" to R.string.virtual_screen_menu).forEach { (key, label) ->
                                IconButton(enabled = display != null && interactionEnabled, onClick = {
                                    display?.let { sendInput(it.sessionId, JSONObject().put("action", "key").put("button", key)) }
                                }, modifier = Modifier.size(48.dp)) { NavigationKey(key, stringResource(label)) }
                            }
                        }
                    }
                }
            }
            EtaWindowDialog(show = showHistory, title = stringResource(R.string.virtual_screen_history), onDismissRequest = { showHistory = false }) {
                if (state.operations.isEmpty()) Text(stringResource(R.string.virtual_screen_history_empty), style = MiuixTheme.textStyles.body2)
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.operations.asReversed(), key = { it.id }) { operation ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(operationLabel(operation.name), color = MiuixTheme.colorScheme.onSurface, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(operation.timestamp)) + " · " +
                                    stringResource(if (operation.manual) R.string.virtual_screen_history_manual else R.string.virtual_screen_history_agent),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
                            }
                            Text(stringResource(if (operation.success) R.string.virtual_screen_operation_success else R.string.virtual_screen_operation_failed),
                                color = if (operation.success) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
                                style = MiuixTheme.textStyles.footnote1)
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        surface?.clearFrame(); hasFrame = false; lastFrameId = 0
        super.onPause()
    }
}

@Composable
private fun TaskIndicator(state: VirtualScreenViewerState, onClick: () -> Unit) {
    val color = when (state.taskPhase) {
        VirtualScreenTaskPhase.RUNNING -> MiuixTheme.colorScheme.primary
        VirtualScreenTaskPhase.FAILED -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val label = when (state.taskPhase) {
        VirtualScreenTaskPhase.RUNNING -> R.string.virtual_screen_live
        VirtualScreenTaskPhase.COMPLETED -> R.string.virtual_screen_completed
        VirtualScreenTaskPhase.FAILED -> R.string.virtual_screen_failed
        VirtualScreenTaskPhase.STOPPED -> R.string.virtual_screen_stopped
        VirtualScreenTaskPhase.IDLE -> R.string.virtual_screen_idle
    }
    Row(Modifier.clip(CircleShape).background(color.copy(alpha = 0.1f))
        .clickable(onClickLabel = stringResource(R.string.virtual_screen_history), onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(color))
        Text(stringResource(label), color = color, style = MiuixTheme.textStyles.footnote1)
    }
}

@Composable
private fun operationLabel(name: String): String = when (name) {
    "home" -> stringResource(R.string.virtual_screen_home)
    "menu", "switch_task" -> stringResource(R.string.virtual_screen_menu)
    "back" -> stringResource(R.string.action_back)
    "enter" -> toolDisplayName("press_key")
    "create" -> stringResource(R.string.virtual_screen_started)
    else -> toolDisplayName(name)
}

@Composable
private fun NavigationKey(key: String, description: String) {
    val color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Canvas(Modifier.size(20.dp).then(Modifier.semantics { contentDescription = description })) {
        val stroke = Stroke(1.8.dp.toPx())
        when (key) {
            "BACK" -> drawPath(Path().apply { moveTo(size.width * 0.75f, 0f); lineTo(size.width * 0.1f, size.height / 2)
                lineTo(size.width * 0.75f, size.height); close() }, color, style = stroke)
            "HOME" -> drawCircle(color, size.minDimension / 2 - stroke.width, style = stroke)
            else -> drawRoundRect(color, topLeft = Offset(stroke.width, stroke.width),
                size = Size(size.width - 2 * stroke.width, size.height - 2 * stroke.width),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = stroke)
        }
    }
}
