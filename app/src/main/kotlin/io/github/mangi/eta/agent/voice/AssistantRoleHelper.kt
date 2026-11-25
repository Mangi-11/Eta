package io.github.mangi.eta.agent.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings

internal object AssistantRoleHelper {
    fun buildSystemSettingsIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
    )

    fun openSystemSettings(context: Context): Boolean {
        val needsNewTask = context !is Activity
        val packageManager = context.packageManager
        for (intent in buildSystemSettingsIntents()) {
            if (needsNewTask) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(packageManager) != null &&
                runCatching { context.startActivity(intent) }.isSuccess
            ) {
                return true
            }
        }
        return false
    }
}
