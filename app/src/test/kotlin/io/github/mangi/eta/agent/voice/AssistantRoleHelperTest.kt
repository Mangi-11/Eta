package io.github.mangi.eta.agent.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `openSystemSettings prefers voice input settings and falls back to default apps settings`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()

        assertFalse(AssistantRoleHelper.openSystemSettings(activity))

        registerResolvableAction(activity, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        assertTrue(AssistantRoleHelper.openSystemSettings(activity))
        assertEquals(
            Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            shadowOf(activity).nextStartedActivity?.action,
        )

        registerResolvableAction(activity, Settings.ACTION_VOICE_INPUT_SETTINGS)
        assertTrue(AssistantRoleHelper.openSystemSettings(activity))
        assertEquals(
            Settings.ACTION_VOICE_INPUT_SETTINGS,
            shadowOf(activity).nextStartedActivity?.action,
        )
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
}
