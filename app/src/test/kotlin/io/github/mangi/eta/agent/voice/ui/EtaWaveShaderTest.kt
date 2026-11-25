package io.github.mangi.eta.agent.voice.ui

import android.app.Application
import android.graphics.RuntimeShader
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EtaWaveShaderTest {
    @Test fun effectCompilesWithAndroidShaderCompiler() {
        try {
            RuntimeShader("uniform shader child; half4 main(float2 p) { return child.eval(p); }")
        } catch (error: IllegalArgumentException) {
            // Robolectric native runtime 的子着色器支持见 upstream issue #9691。
            assumeNoException("Native test runtime lacks child shader support", error)
        }
        val source = RuntimeEnvironment.getApplication().assets.open("assistant/wave.agsl").bufferedReader().use { it.readText() }
        RuntimeShader(source)
    }
}
