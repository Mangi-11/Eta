package io.github.mangi.eta.agent.voice

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun EtaAssistantWave(
    active: Boolean,
    level: () -> Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentLevel by rememberUpdatedState(level)
    val effect by produceState<EtaWaveEffect?>(null) {
        value = withContext(Dispatchers.IO) {
            try {
                EtaWaveEffect(context)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                AndroidAgentLogger.warn("Eta assistant wave unavailable: type=${error.javaClass.simpleName}")
                null
            }
        }
    }
    var elapsed by remember { mutableFloatStateOf(0f) }
    var smoothedLevel by remember { mutableFloatStateOf(0f) }
    var unavailable by remember { mutableStateOf(false) }
    var entranceReachedBottom by remember { mutableStateOf(false) }
    var waveVisible by remember { mutableStateOf(false) }
    val clock = remember { longArrayOf(0L) }

    val waveAlpha by animateFloatAsState(
        targetValue = if (active && entranceReachedBottom) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (active) 220 else 280,
            easing = FastOutSlowInEasing,
        ),
        label = "assistant_wave_alpha",
    )

    LaunchedEffect(Unit) {
        delay(ASSISTANT_EDGE_ARRIVAL_MS)
        entranceReachedBottom = true
    }
    LaunchedEffect(active, effect, unavailable, entranceReachedBottom) {
        if (effect == null || unavailable) {
            waveVisible = false
            return@LaunchedEffect
        }
        if (!entranceReachedBottom || (!active && !waveVisible)) return@LaunchedEffect
        var stoppedAt = -1f
        while (isActive) {
            withFrameNanos { now ->
                if (clock[0] == 0L) clock[0] = now
                elapsed = (now - clock[0]) / 1_000_000_000f
                val raw = if (active) currentLevel().coerceIn(0f, 1f) else 0f
                val factor = if (raw > smoothedLevel) 0.28f else 0.10f
                smoothedLevel += (raw - smoothedLevel) * factor
                waveVisible = true
            }
            if (!active) {
                if (stoppedAt < 0f) stoppedAt = elapsed
                if (elapsed - stoppedAt >= 0.32f) break
            }
        }
        if (!active) waveVisible = false
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .graphicsLayer { alpha = waveAlpha },
    ) {
        if (!entranceReachedBottom || !waveVisible || waveAlpha <= 0.005f) return@Canvas
        // 部分 ROM 在窗口切换时使用软件画布，RuntimeShader 只能提交到硬件画布。
        if (!unavailable && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
            effect?.let {
                try {
                    it.update(elapsed, active, smoothedLevel, size.width, size.height)
                    drawRect(it.brush)
                } catch (error: RuntimeException) {
                    unavailable = true
                    AndroidAgentLogger.warn("Eta assistant wave draw failed: type=${error.javaClass.simpleName}")
                }
            }
        }
    }
}

/** 只播放打包资源中的数值轨道，不执行资源里的脚本。 */
internal class EtaWaveEffect(context: Context) {
    private val data = context.assets.open("assistant/wave.json").bufferedReader().use { JSONObject(it.readText()) }
    private val shader = RuntimeShader(context.assets.open("assistant/wave.agsl").bufferedReader().use { it.readText() })
    val brush = ShaderBrush(shader)
    private val values = linkedMapOf<String, FloatArray>()
    private val animations: Map<String, List<Track>>
    private var mode = "Default"
    private var startedAt = 0f
    private var previousTime = 0f
    private var origin = emptyMap<String, FloatArray>()
    private var wasActive = false
    private val angles = FloatArray(3)
    private val animatedNames: Set<String>
    private var lastWidth = -1f
    private var lastHeight = -1f

    init {
        val uniforms = data.getJSONArray("uniforms")
        for (index in 0 until uniforms.length()) {
            val uniform = uniforms.getJSONObject(index)
            val name = uniform.getString("uniformName")
            when (uniform.getString("type")) {
                "Texture" -> {
                    val bitmap = context.assets.open("assistant/$name.png").use { requireNotNull(BitmapFactory.decodeStream(it)) }
                    val sampler = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
                    sampler.setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                    shader.setInputShader(name, sampler)
                }
                "float" -> values[name] = floatArrayOf(uniform.getDouble("value").toFloat())
                "Vec2" -> values[name] = floatArrayOf(uniform.getDouble("x").toFloat(), uniform.getDouble("y").toFloat())
                else -> error("Unsupported wave uniform")
            }
        }
        val definitions = data.getJSONArray("animations")
        animations = buildMap {
            for (index in 0 until definitions.length()) {
                val definition = definitions.getJSONObject(index)
                val lines = definition.getJSONArray("animLines")
                put(definition.getString("name"), buildList {
                    for (lineIndex in 0 until lines.length()) {
                        val line = lines.getJSONObject(lineIndex)
                        val keys = line.getJSONObject("animKeys")
                        for (axis in keys.keys()) {
                            add(Track(line.getString("name"), if (axis == "y") 1 else 0, parseKeys(keys.getJSONArray(axis))))
                        }
                    }
                })
            }
        }
        apply("Default", 0f, 0f)
        values.getValue("r")[0] = 0f
        values.getValue("superN")[0] = 2f
        animatedNames = buildSet {
            animations.filterKeys { it != "Default" }.values.forEach { tracks ->
                tracks.forEach { add(it.name) }
            }
            add("u_time")
            add("u_speed1")
            add("u_Angle2")
            add("u_Angle3")
            add("u_Angle4")
        }
        for ((name, value) in values) shader.setFloatUniform(name, value)
    }

    fun update(time: Float, active: Boolean, level: Float, width: Float, height: Float) {
        if (active != wasActive) {
            switch(if (active) "Wave On" else "Wave Off", time)
            wasActive = active
        }
        if (active && time - startedAt >= 0.4f && mode == "Wave On") switch("Voice NoListen", time)
        if (active && mode != "Wave On") {
            val speaking = level > 0.05f
            when {
                speaking && mode == "Voice NoListen" -> switch("Voice Found", time)
                !speaking && (mode == "Voice Found" || mode == "Voice Listen") -> switch("Voice NoListen", time)
                speaking && mode == "Voice Found" && time - startedAt >= 0.25f -> switch("Voice Listen", time)
            }
        }
        apply(mode, time - startedAt, level)
        val dt = (time - previousTime).coerceIn(0f, 0.25f)
        previousTime = time
        val speaking = mode == "Voice Found" || mode == "Voice Listen"
        val speeds = if (speaking) floatArrayOf(1.2f, 1f, 0.7f) else floatArrayOf(0.2f, 0.3f, 0.17f)
        for (index in angles.indices) {
            angles[index] += speeds[index] * dt
            values.getValue("u_Angle${index + 2}")[0] = angles[index]
        }
        values.getValue("u_speed1")[0] = if (speaking) -0.4f else 0.2f
        values.getValue("u_time")[0] = time
        if (width != lastWidth || height != lastHeight) {
            values.getValue("u_resolution")[0] = width
            values.getValue("u_resolution")[1] = height
            shader.setFloatUniform("u_resolution", values.getValue("u_resolution"))
            lastWidth = width
            lastHeight = height
        }
        for (name in animatedNames) shader.setFloatUniform(name, values.getValue(name))
    }

    private fun switch(next: String, time: Float) {
        origin = values.mapValues { it.value.copyOf() }
        mode = next
        startedAt = time
    }

    private fun apply(name: String, time: Float, level: Float) {
        for (track in animations.getValue(name)) {
            val keys = track.keys
            val right = keys.indexOfFirst { it.time > time }.let { if (it < 0) keys.lastIndex else it }
            val left = (right - 1).coerceAtLeast(0)
            val from = keys[left]
            val to = keys[right]
            val start = resolve(from, track, level)
            val end = resolve(to, track, level)
            val fraction = if (time >= to.time || from.time == to.time) 1f else from.easing.transform(((time - from.time) / (to.time - from.time)).coerceIn(0f, 1f))
            values.getValue(track.name)[track.axis] = start + (end - start) * fraction
        }
    }

    private fun resolve(key: Key, track: Track, level: Float): Float {
        val amount = ((level - 0.6f) / 0.25f).coerceIn(0f, 1f)
        return when (key.expression) {
            "" -> key.value
            "origin" -> origin[track.name]?.get(track.axis) ?: key.value
            "u_amp2", "u_amp3" -> 0.12f + 0.28f * amount
            "u_amp4" -> 0.11f + 0.29f * amount
            "u_maskW" -> 0.75f + 0.25f * amount
            else -> error("Unsupported wave expression")
        }
    }

    private fun parseKeys(array: JSONArray): List<Key> = (0 until array.length()).map { index ->
        val key = array.getJSONObject(index)
        val curve = key.getJSONArray("bezier")
        require(key.getString("ipol") == "bezier")
        Key(key.getDouble("time").toFloat(), key.getDouble("value").toFloat(), key.getString("expr"),
            CubicBezierEasing(curve.getDouble(0).toFloat(), curve.getDouble(1).toFloat(), curve.getDouble(2).toFloat(), curve.getDouble(3).toFloat()))
    }

    private data class Track(val name: String, val axis: Int, val keys: List<Key>)
    private data class Key(val time: Float, val value: Float, val expression: String, val easing: CubicBezierEasing)
}
