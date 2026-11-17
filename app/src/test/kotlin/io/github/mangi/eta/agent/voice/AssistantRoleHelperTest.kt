package io.github.mangi.eta.agent.voice

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import io.github.mangi.eta.core.ModuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AssistantRoleHelperTest {

    @Test
    fun `unheld assistant role dispatches standard role request intent`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val roleIntent = Intent("android.app.role.action.REQUEST_ROLE")
        val gateway = FakeGateway(
            available = true,
            held = false,
            requestIntent = roleIntent,
        )

        val status = AssistantRoleHelper.queryStatus(activity, gateway)
        assertTrue(status.roleAvailable)
        assertFalse(status.isDefaultAssistant)

        var launchedIntent: Intent? = null
        val dispatch = AssistantRoleHelper.dispatchPrimaryEntry(
            context = activity,
            status = status,
            gateway = gateway,
            launchRoleRequest = { launchedIntent = it },
        )

        assertEquals(AssistantEntryDispatch.ROLE_REQUEST, dispatch)
        assertEquals(roleIntent, launchedIntent)
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.destroy()
    }

    @Test
    fun `held assistant role opens voice input settings directly`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        registerResolvableAction(activity, Settings.ACTION_VOICE_INPUT_SETTINGS)
        val gateway = FakeGateway(
            available = true,
            held = true,
            requestIntent = Intent("android.app.role.action.REQUEST_ROLE"),
        )

        val status = AssistantRoleHelper.queryStatus(activity, gateway)
        assertTrue(status.roleAvailable)
        assertTrue(status.isDefaultAssistant)

        var roleRequested = false
        val dispatch = AssistantRoleHelper.dispatchPrimaryEntry(
            context = activity,
            status = status,
            gateway = gateway,
            launchRoleRequest = { roleRequested = true },
        )

        assertEquals(AssistantEntryDispatch.SYSTEM_SETTINGS, dispatch)
        assertFalse(roleRequested)
        assertEquals(
            Settings.ACTION_VOICE_INPUT_SETTINGS,
            shadowOf(activity).nextStartedActivity?.action,
        )
        controller.destroy()
    }

    @Test
    fun `role request launch failure falls back to default assistant settings`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        registerResolvableAction(activity, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        val gateway = FakeGateway(
            available = true,
            held = false,
            requestIntent = Intent("android.app.role.action.REQUEST_ROLE"),
        )

        val dispatch = AssistantRoleHelper.dispatchPrimaryEntry(
            context = activity,
            status = AssistantRoleStatus(roleAvailable = true, isDefaultAssistant = false),
            gateway = gateway,
            launchRoleRequest = { error("request role blocked") },
        )

        assertEquals(AssistantEntryDispatch.SYSTEM_SETTINGS, dispatch)
        assertEquals(
            Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            shadowOf(activity).nextStartedActivity?.action,
        )
        controller.destroy()
    }

    @Test
    fun `unavailable role falls back to secure assistant setting for status and reports failed when no settings screen exists`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val component = ComponentName(activity, EtaVoiceInteractionService::class.java)
        Settings.Secure.putString(
            activity.contentResolver,
            ModuleConfig.SECURE_ASSISTANT,
            component.flattenToShortString(),
        )
        val gateway = FakeGateway(
            available = false,
            held = false,
            requestIntent = null,
        )

        val status = AssistantRoleHelper.queryStatus(activity, gateway)
        assertFalse(status.roleAvailable)
        assertTrue(status.isDefaultAssistant)
        assertEquals(DefaultAssistantOwner.ETA, status.owner)

        val dispatch = AssistantRoleHelper.dispatchPrimaryEntry(
            context = activity,
            status = status,
            gateway = gateway,
            launchRoleRequest = { error("should not request unavailable role") },
        )
        assertEquals(AssistantEntryDispatch.FAILED, dispatch)
        controller.destroy()
    }

    @Test
    fun `detects gemini and other active default assistants from secure settings`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val gateway = FakeGateway(
            available = true,
            held = false,
            requestIntent = Intent("android.app.role.action.REQUEST_ROLE"),
        )

        Settings.Secure.putString(
            activity.contentResolver,
            ModuleConfig.SECURE_ASSISTANT,
            ModuleConfig.GOOGLE_ASSISTANT_COMPONENT,
        )
        val geminiStatus = AssistantRoleHelper.queryStatus(activity, gateway)
        assertFalse(geminiStatus.isDefaultAssistant)
        assertEquals(DefaultAssistantOwner.GEMINI, geminiStatus.owner)
        assertEquals("Gemini", geminiStatus.ownerLabel)

        Settings.Secure.putString(
            activity.contentResolver,
            ModuleConfig.SECURE_ASSISTANT,
            "com.example.other/.AssistService",
        )
        val otherStatus = AssistantRoleHelper.queryStatus(activity, gateway)
        assertFalse(otherStatus.isDefaultAssistant)
        assertEquals(DefaultAssistantOwner.OTHER, otherStatus.owner)

        Settings.Secure.putString(activity.contentResolver, ModuleConfig.SECURE_ASSISTANT, "")
        Settings.Secure.putString(
            activity.contentResolver,
            ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE,
            "",
        )
        val noneStatus = AssistantRoleHelper.queryStatus(activity, gateway)
        assertEquals(DefaultAssistantOwner.NONE, noneStatus.owner)
        controller.destroy()
    }

    private fun registerResolvableAction(context: Context, action: String) {
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.settings"
                name = "com.android.settings.FakeSettingsActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(action), resolveInfo)
    }

    private class FakeGateway(
        private val available: Boolean,
        private val held: Boolean,
        private val requestIntent: Intent?,
    ) : AssistantRoleGateway {
        override fun isRoleAvailable(context: Context): Boolean = available
        override fun isRoleHeld(context: Context): Boolean = held
        override fun createRequestRoleIntent(context: Context): Intent? = requestIntent
    }
}
