package io.github.mangi.eta.core

import android.content.Context
import android.content.res.Configuration
import io.github.mangi.eta.R
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppStringsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        AppStrings.resetForTest()
    }

    @Test
    fun fallsBackToTemplateWhenContextIsNotInitialized() {
        AppStrings.resetForTest()
        assertEquals(
            "字段 model",
            AppStrings.get(R.string.provider_param_field, "字段 %1\$s", "model"),
        )
        assertEquals(
            "1 个",
            AppStrings.quantity(R.plurals.provider_models_count, 1, "%1\$d 个", "%1\$d 个", 1),
        )
        assertEquals(
            "3 个",
            AppStrings.quantity(R.plurals.provider_models_count, 3, "%1\$d 个", "%1\$d 个", 3),
        )
    }

    @Test
    fun resolvesUpdatedLocaleDynamicallyWithoutCachingOldLanguage() {
        AppStrings.init(context)

        applyLocale("en-US")
        assertEquals(
            "Invalid Vertex AI endpoint URL.",
            AppStrings.get(R.string.provider_url_vertex_invalid, "Vertex AI 接口地址无效。"),
        )
        assertEquals(
            "1 model",
            AppStrings.quantity(R.plurals.provider_models_count, 1, "%1\$d 个模型", "%1\$d 个模型", 1),
        )
        assertEquals(
            "2 models",
            AppStrings.quantity(R.plurals.provider_models_count, 2, "%1\$d 个模型", "%1\$d 个模型", 2),
        )

        applyLocale("zh-CN")
        assertEquals(
            "Vertex AI 接口地址无效。",
            AppStrings.get(R.string.provider_url_vertex_invalid, "Vertex AI 接口地址无效。"),
        )

        applyLocale("zh-TW")
        assertEquals(
            "Vertex AI 接口位址無效。",
            AppStrings.get(R.string.provider_url_vertex_invalid, "Vertex AI 接口地址无效。"),
        )

        applyLocale("fr-FR")
        assertEquals(
            "Invalid Vertex AI endpoint URL.",
            AppStrings.get(R.string.provider_url_vertex_invalid, "Vertex AI 接口地址无效。"),
        )
    }

    @Suppress("DEPRECATION")
    private fun applyLocale(languageTag: String) {
        val resources = context.resources
        val configuration = Configuration(resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }
}
