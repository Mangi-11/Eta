package io.github.mangi.eta.agent.voice.asr

import android.app.Application
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.mangi.eta.agent.voice.EtaRecognitionService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SystemSpeechRecognizerTest {
    private fun service(
        packageName: String,
        name: String,
        permission: String? = null,
        exported: Boolean = true,
    ) = ResolveInfo().apply {
        serviceInfo = ServiceInfo().apply {
            this.packageName = packageName
            this.name = name
            this.permission = permission
            this.exported = exported
            this.enabled = true
            applicationInfo = ApplicationInfo().apply { enabled = true }
        }
    }

    @Test fun configuredRecognitionServiceCanOmitBindPermission() {
        val configured = ComponentName("com.google.android.tts", "GoogleTTSRecognitionService")
        assertEquals(
            configured,
            SystemSpeechRecognizer.selectExternalService(
                listOf(service(configured.packageName, configured.className)),
                "io.github.mangi.eta",
                configured,
            ),
        )
    }

    @Test fun unconfiguredServiceWithoutBindPermissionIsNotSelected() {
        assertNull(
            SystemSpeechRecognizer.selectExternalService(
                listOf(service("third.party", "Recognizer")),
                "io.github.mangi.eta",
                null,
            ),
        )
    }

    @Test fun unexportedAndSelfRecognitionServicesAreExcluded() {
        val configured = ComponentName("third.party", "Hidden")
        assertNull(
            SystemSpeechRecognizer.selectExternalService(
                listOf(
                    service("third.party", "Hidden", exported = false),
                    service("io.github.mangi.eta", "EtaRecognitionService", permission = "android.permission.BIND_SPEECH_RECOGNITION_SERVICE"),
                ),
                "io.github.mangi.eta",
                configured,
            ),
        )
    }
}
