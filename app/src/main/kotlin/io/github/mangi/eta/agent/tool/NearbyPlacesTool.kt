package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import io.github.mangi.eta.core.AgentLogger
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 基于最近系统位置查询周边地点。
 *
 * 位置来自 [DeviceLocationProvider]（只读最近定位，不唤醒 GPS）；
 * POI 检索走 Overpass 公共端点，不需要额外 API Key。
 * 查询失败时返回结构化错误，不编造结果。
 */
internal class NearbyPlacesTool(
    private val context: Context,
    private val logger: AgentLogger,
) {
    fun search(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isBlank()) return error("INVALID_ARGUMENT", "缺少搜索关键词")
        val radius = args.optInt("radius_m", DEFAULT_RADIUS).coerceIn(MIN_RADIUS, MAX_RADIUS)
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val location = DeviceLocationProvider.latest(context)
        val available = location as? DeviceLocationProvider.Result.Available
            ?: return error(
                "LOCATION_UNAVAILABLE",
                "无法获取当前位置，原因：${(location as? DeviceLocationProvider.Result.Unavailable)?.status ?: "unknown"}",
            )
        if (available.ageMillis > MAX_LOCATION_AGE_MILLIS) {
            return error(
                "LOCATION_STALE",
                "最近定位已过期（约 ${available.ageMillis / 60_000L} 分钟前），无法用于周边搜索",
            )
        }

        val results = runCatching {
            queryOverpass(query, available.latitude, available.longitude, radius, limit)
        }.getOrElse { throwable ->
            logger.warn(
                "nearby_places_failed: type=${throwable.javaClass.simpleName}, " +
                    "message=${throwable.message ?: "unknown"}",
            )
            null
        } ?: return error("SEARCH_FAILED", "周边地点检索失败，可稍后重试")

        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("latitude", available.latitude)
            .put("longitude", available.longitude)
            .put("radius_m", radius)
            .put("count", results.length())
            .put("places", results)
            .toString()
    }

    private fun queryOverpass(
        query: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        limit: Int,
    ): JSONArray {
        val sanitized = query.replace(Regex("[\"\\\\]"), " ").trim()
        val overpassQuery = buildString {
            append("[out:json][timeout:25];")
            append("(")
            append("nwr[\"name\"~\"$sanitized\",i](around:$radiusMeters,$latitude,$longitude);")
            append("nwr[\"brand\"~\"$sanitized\",i](around:$radiusMeters,$latitude,$longitude);")
            append("nwr[\"amenity\"~\"$sanitized\",i](around:$radiusMeters,$latitude,$longitude);")
            append("nwr[\"shop\"~\"$sanitized\",i](around:$radiusMeters,$latitude,$longitude);")
            append(");")
            append("out center tags $limit;")
        }

        val request = Request.Builder()
            .url("$OVERPASS_ENDPOINT?data=${URLEncoder.encode(overpassQuery, "UTF-8")}")
            .header("User-Agent", AGENT_USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("overpass_http_${response.code}")
            }
            val body = response.body?.string()
                ?: throw IllegalStateException("overpass_empty_body")

            val parsed = JSONObject(body)
            val elements = parsed.optJSONArray("elements") ?: JSONArray()
            val places = JSONArray()
            for (index in 0 until elements.length()) {
                val element = elements.getJSONObject(index)
                val tags = element.optJSONObject("tags") ?: continue
                val name = tags.optString("name").trim()
                if (name.isBlank()) continue
                val center = element.optJSONObject("center")
                val lat = center?.optDouble("latitude") ?: element.optDouble("lat")
                val lon = center?.optDouble("longitude") ?: element.optDouble("lon")
                val place = JSONObject()
                    .put("name", name)
                    .put("latitude", lat)
                    .put("longitude", lon)
                tags.optString("amenity").takeIf(String::isNotBlank)?.let { place.put("category", it) }
                tags.optString("shop").takeIf(String::isNotBlank)?.let { place.put("category", it) }
                tags.optString("brand").takeIf(String::isNotBlank)?.let { place.put("brand", it) }
                tags.optString("phone").takeIf(String::isNotBlank)?.let { place.put("phone", it) }
                tags.optString("opening_hours").takeIf(String::isNotBlank)?.let { place.put("opening_hours", it) }
                val address = buildString {
                    tags.optString("addr:housenumber").takeIf(String::isNotBlank)?.let { append(it).append(' ') }
                    tags.optString("addr:street").takeIf(String::isNotBlank)?.let { append(it) }
                    tags.optString("addr:district").takeIf(String::isNotBlank)?.let {
                        if (isNotEmpty()) append(' ')
                        append(it)
                    }
                    tags.optString("addr:city").takeIf(String::isNotBlank)?.let {
                        if (isNotEmpty()) append(' ')
                        append(it)
                    }
                }.trim().ifBlank { null }?.let { place.put("address", it) }
                place.put(
                    "distance_m",
                    haversineMeters(latitude, longitude, lat, lon).toInt(),
                )
                places.put(place)
            }
            return places
        }
    }

    private fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val sinHalfLat = Math.sin(dLat / 2)
        val sinHalfLon = Math.sin(dLon / 2)
        val a = sinHalfLat * sinHalfLat +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinHalfLon * sinHalfLon
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    private fun error(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private companion object {
        const val OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"
        const val AGENT_USER_AGENT = "Eta/1.0 (Android Agent)"
        const val DEFAULT_RADIUS = 1_500
        const val MIN_RADIUS = 100
        const val MAX_RADIUS = 10_000
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 30
        const val MAX_LOCATION_AGE_MILLIS = 10 * 60_000L
        const val EARTH_RADIUS_METERS = 6_371_000.0

        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build()
        }
    }
}
