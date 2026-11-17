package io.github.mangi.eta.agent.voice

import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import io.github.mangi.eta.core.ModuleConfig

internal enum class DefaultAssistantOwner {
    ETA,
    GEMINI,
    OTHER,
    NONE,
}

internal data class AssistantRoleStatus(
    val isDefaultAssistant: Boolean,
    val owner: DefaultAssistantOwner =
        if (isDefaultAssistant) DefaultAssistantOwner.ETA else DefaultAssistantOwner.NONE,
    val ownerLabel: String? = null,
)

internal interface AssistantRoleGateway {
    fun isRoleAvailable(context: Context): Boolean
    fun isRoleHeld(context: Context): Boolean
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
}

internal object AssistantRoleHelper {
    fun queryStatus(
        context: Context,
        gateway: AssistantRoleGateway = SystemAssistantRoleGateway,
    ): AssistantRoleStatus {
        val roleAvailable = gateway.isRoleAvailable(context)
        val isEtaRoleHeld = if (roleAvailable) gateway.isRoleHeld(context) else false
        val configuredComponent = readSecureAssistantComponent(context)
        if (isEtaRoleHeld || (!roleAvailable && isEtaComponent(context, configuredComponent))) {
            return AssistantRoleStatus(
                isDefaultAssistant = true,
                owner = DefaultAssistantOwner.ETA,
            )
        }

        val packageName = configuredComponent?.let(::extractPackageName)
        return when {
            packageName == ModuleConfig.GOOGLE_PACKAGE -> AssistantRoleStatus(
                isDefaultAssistant = false,
                owner = DefaultAssistantOwner.GEMINI,
                ownerLabel = "Gemini",
            )
            !packageName.isNullOrEmpty() && packageName != ModuleConfig.ETA_PACKAGE -> AssistantRoleStatus(
                isDefaultAssistant = false,
                owner = DefaultAssistantOwner.OTHER,
                ownerLabel = resolveAppLabel(context, packageName),
            )
            else -> AssistantRoleStatus(
                isDefaultAssistant = false,
                owner = DefaultAssistantOwner.NONE,
            )
        }
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

    private fun readSecureAssistantComponent(context: Context): String? {
        val assistant = runCatching {
            Settings.Secure.getString(context.contentResolver, ModuleConfig.SECURE_ASSISTANT)
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        if (assistant != null) return assistant
        return runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE,
            )
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun isEtaComponent(context: Context, configured: String?): Boolean {
        if (configured.isNullOrEmpty()) return false
        val expected = ComponentName(context, EtaVoiceInteractionService::class.java)
        val parsed = ComponentName.unflattenFromString(configured)
        return parsed == expected || configured == expected.flattenToShortString()
    }

    private fun extractPackageName(configured: String): String? =
        ComponentName.unflattenFromString(configured)?.packageName
            ?: configured.substringBefore('/').trim().takeIf { it.isNotEmpty() }

    private fun resolveAppLabel(context: Context, packageName: String): String? =
        runCatching {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
}
