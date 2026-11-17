package io.github.mangi.eta.agent.voice

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import io.github.mangi.eta.core.AssistantContextFlags

class EtaVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        activeService = this
        if (Build.VERSION.SDK_INT >= 37) {
            setInvocationEffectEnabled(true)
        }
    }

    override fun onShutdown() {
        if (activeService === this) activeService = null
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        startActivity(
            Intent(this, EtaVoiceAssistActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun showEtaSession() {
        showSession(Bundle(), AssistantContextFlags.forSdk(Build.VERSION.SDK_INT))
    }

    companion object {
        @Volatile
        private var activeService: EtaVoiceInteractionService? = null

        internal fun requestSession(): Boolean {
            val service = activeService ?: return false
            service.showEtaSession()
            return true
        }
    }
}

class EtaVoiceAssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!EtaVoiceInteractionService.requestSession()) {
            EtaAssistantOverlayService.show(this, null)
        }
        finish()
    }
}
