package io.github.mangi.eta.agent.voice

import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import io.github.mangi.eta.core.ModuleConfig

internal data class AssistantRoleStatus(
    val roleAvailable: Boolean,
    val isDefaultAssistant: Boolean,
)

internal enum class AssistantEntryDispatch {
    ROLE_REQUEST,
    SYSTEM_SETTINGS,
    FAILED,
}

internal interface AssistantRoleGateway {
    fun isRoleAvailable(context: Context): Boolean
    fun isRoleHeld(context: Context): Boolean
    fun createRequestRoleIntent(context: Context): Intent?
}

internal object SystemAssistantRoleGateway : AssistantRoleGateway {
    override fun isRoleAvailable(context: Context): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    }

    override fun isRoleHeld(context: Context): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return runCatching { roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    }

    override fun createRequestRoleIntent(context: Context): Intent? {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return null
        return runCatching { roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT) }.getOrNull()
    }
}

internal object AssistantRoleHelper {
    fun queryStatus(
        context: Context,
        gateway: AssistantRoleGateway = SystemAssistantRoleGateway,
    ): AssistantRoleStatus {
        val roleAvailable = gateway.isRoleAvailable(context)
        val isDefault = if (roleAvailable) {
            gateway.isRoleHeld(context)
        } else {
            isConfiguredInSecureSettings(context)
        }
        return AssistantRoleStatus(
            roleAvailable = roleAvailable,
            isDefaultAssistant = isDefault,
        )
    }

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

    fun dispatchPrimaryEntry(
        context: Context,
        status: AssistantRoleStatus = queryStatus(context),
        gateway: AssistantRoleGateway = SystemAssistantRoleGateway,
        launchRoleRequest: (Intent) -> Unit,
    ): AssistantEntryDispatch {
        if (!status.isDefaultAssistant && status.roleAvailable) {
            val requestIntent = gateway.createRequestRoleIntent(context)
            if (requestIntent != null && runCatching { launchRoleRequest(requestIntent) }.isSuccess) {
                return AssistantEntryDispatch.ROLE_REQUEST
            }
        }
        return if (openSystemSettings(context)) {
            AssistantEntryDispatch.SYSTEM_SETTINGS
        } else {
            AssistantEntryDispatch.FAILED
        }
    }

    private fun isConfiguredInSecureSettings(context: Context): Boolean {
        val configured = runCatching {
            Settings.Secure.getString(context.contentResolver, ModuleConfig.SECURE_ASSISTANT)
        }.getOrNull()?.trim().orEmpty()
        if (configured.isEmpty()) return false
        val expected = ComponentName(context, EtaVoiceInteractionService::class.java)
        val parsed = ComponentName.unflattenFromString(configured)
        return parsed == expected || configured == expected.flattenToShortString()
    }
}
