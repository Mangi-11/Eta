package io.github.mangi.eta.data.repository.catalog

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.provider.AgentHttpClient
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.provider.CommunityCatalog
import io.github.mangi.eta.data.provider.CommunityCatalogParser
import io.github.mangi.eta.data.provider.CommunityCatalogProvider
import io.github.mangi.eta.data.provider.CommunityCatalogSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Request

internal object CommunityCatalogRepository {
    private const val CATALOG_URL = "https://models.dev/api.json"
    private const val SNAPSHOT_ASSET = "catalog/models-dev-api.json.gzip"
    private const val CACHE_FILE = "catalog/models-dev-api.json"
    private const val MAX_JSON_BYTES = 8 * 1024 * 1024

    private val httpClient by lazy {
        AgentHttpClient.client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun loadLocal(context: Context): CommunityCatalog = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val cache = cacheFile(appContext)
        try {
            return@withContext CommunityCatalogParser.parse(
                raw = AtomicFile(cache).openRead().use { it.readUtf8Limited() },
                source = CommunityCatalogSource.CACHE,
                fetchedAt = cache.lastModified(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 缓存缺失或损坏时仍可读取随 APK 打包的目录。
        }
        CommunityCatalogParser.parse(
            raw = appContext.assets.open(SNAPSHOT_ASSET).use { asset ->
                GZIPInputStream(asset).use { it.readUtf8Limited() }
            },
            source = CommunityCatalogSource.SNAPSHOT,
        )
    }

    suspend fun refresh(context: Context): Result<CommunityCatalog> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(CATALOG_URL)
                .header("Accept", "application/json")
                .header("User-Agent", "Eta-Model-Catalog (+https://github.com/Mangi-11/Eta)")
                .get()
                .build()
            // 目录请求不读取也不转发任何已保存的模型服务 API Key。
            val call = httpClient.newCall(request)
            val cancellation = currentCoroutineContext().job.invokeOnCompletion { cause ->
                if (cause is CancellationException) call.cancel()
            }
            val raw = try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        error(
                            AppStrings.get(
                                R.string.repo_err_catalog_update_http,
                                "目录更新失败：HTTP %1\$d",
                                response.code,
                            ),
                        )
                    }
                    if (!response.header("Content-Type").orEmpty().startsWith("application/json", ignoreCase = true)) {
                        error(
                            AppStrings.get(
                                R.string.repo_err_catalog_update_not_json,
                                "目录更新失败：响应格式不是 JSON",
                            ),
                        )
                    }
                    response.body.byteStream().use { it.readUtf8Limited() }
                }
            } finally {
                cancellation.dispose()
            }
            val catalog = try {
                CommunityCatalogParser.parse(
                    raw = raw,
                    source = CommunityCatalogSource.ONLINE,
                    fetchedAt = System.currentTimeMillis(),
                )
            } catch (failure: Exception) {
                throw IllegalStateException(
                    AppStrings.get(
                        R.string.repo_err_catalog_invalid_format,
                        "目录数据格式无效",
                    ),
                    failure,
                )
            }
            val file = AtomicFile(cacheFile(context.applicationContext))
            val output = file.startWrite()
            try {
                output.write(raw.toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
            Result.success(catalog)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }

    fun newProvider(selection: CommunityCatalogProvider, modelIds: Set<String>): ProviderSetting {
        require(modelIds.isNotEmpty()) {
            AppStrings.get(
                R.string.repo_err_catalog_select_model_required,
                "请至少选择一个模型",
            )
        }
        val models = selection.models.filter { it.modelId in modelIds }
        require(models.size == modelIds.size) {
            AppStrings.get(
                R.string.repo_err_catalog_selected_model_missing,
                "选中的模型已不在目录中，请重新选择",
            )
        }
        return CustomProviderSetting(
            id = UUID.randomUUID().toString(),
            name = selection.name,
            baseUrl = selection.baseUrl,
            isEnabled = false,
            models = models.mapIndexed { index, model ->
                Model(
                    id = UUID.randomUUID().toString(),
                    modelId = model.modelId,
                    displayName = model.displayName,
                    isBuiltIn = false,
                    sortOrder = index,
                    source = ModelSource.CATALOG,
                    contextWindow = model.contextWindow,
                    inputModalities = model.inputModalities,
                    toolCall = true,
                    reasoning = model.reasoning,
                    structuredOutput = model.structuredOutput,
                    supportsTemperature = model.supportsTemperature,
                )
            },
        )
    }

    private fun cacheFile(context: Context): File =
        File(context.filesDir, CACHE_FILE).also { it.parentFile?.mkdirs() }

    private fun InputStream.readUtf8Limited(): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_JSON_BYTES) {
                error(
                    AppStrings.get(
                        R.string.repo_err_catalog_size_limit,
                        "模型目录超出大小限制",
                    ),
                )
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
