package com.codecandy.blinkify

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OwnAodWeatherWorker - Fetches weather data for the Custom AOD weather element.
 *
 * PRIVACY NOTICE
 *
 * This worker runs ONLY if the user has explicitly enabled the weather
 * feature in the app settings. If the feature is disabled, this worker
 * returns immediately without making any network requests.
 *
 * External service used: Open-Meteo (https://open-meteo.com)
 *   - No API key, no registration, no user account
 *   - No cookies, no tracking, no fingerprinting
 *   - Open-source, non-commercial, privacy-friendly weather service
 *   - Based in Switzerland (EU adequacy decision applies)
 *
 * What IS sent to Open-Meteo:
 *   - The city name the user typed (for geocoding)
 *   - The resulting lat/lon coordinates (for forecast lookup)
 *   - The device's public IP address (unavoidable for any HTTP request)
 *
 * What is NOT sent:
 *   - No device ID, no advertising ID
 *   - No user name, no email, no contacts
 *   - No authentication tokens (none exist)
 *   - No analytics, no telemetry
 *
 * All requests use HTTPS. No other third-party services are contacted.
 */
class OwnAodWeatherWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val ctx = applicationContext

            // PRIVACY: If the user has disabled the weather feature,
            // do nothing at all — no network request, no data written.
            if (!AppPrefs.getOwnAodWeatherEnabled(ctx)) {
                return@withContext Result.success()
            }

            val location = AppPrefs.getOwnAodWeatherLocation(ctx).ifBlank { "Stuttgart" }

            // 1) Geocoding -> lat/lon (Open-Meteo)
            val (lat, lon, _) = geocode(location) ?: run {
                AppPrefs.setOwnAodWeatherLine(ctx, "Wetter: —")
                AppPrefs.setOwnAodWeatherCode(ctx, 0)
                AppPrefs.setOwnAodWeatherUpdatedAt(ctx, System.currentTimeMillis())
                return@withContext Result.retry()
            }

            // 2) Forecast for today: min/max + weathercode (Open-Meteo)
            val forecast = fetchTodayForecast(lat, lon) ?: run {
                AppPrefs.setOwnAodWeatherLine(ctx, "Wetter: —")
                AppPrefs.setOwnAodWeatherCode(ctx, 0)
                AppPrefs.setOwnAodWeatherUpdatedAt(ctx, System.currentTimeMillis())
                return@withContext Result.retry()
            }

            val (tMin, tMax, code) = forecast
            val line = "max. ${tMax}°   min. ${tMin}°"

            AppPrefs.setOwnAodWeatherLine(ctx, line)
            AppPrefs.setOwnAodWeatherCode(ctx, code)
            AppPrefs.setOwnAodWeatherTempMax(ctx, tMax)
            AppPrefs.setOwnAodWeatherTempMin(ctx, tMin)
            AppPrefs.setOwnAodWeatherUpdatedAt(ctx, System.currentTimeMillis())

            Result.success()
        } catch (e: Exception) {
            // PRIVACY: Only logs the exception class name (e.g.
            // "UnknownHostException") — never URLs, city names, or
            // any other data that could identify the user.
            Log.w("Blinkify", "Weather worker error: ${e.javaClass.simpleName}")
            Result.retry()
        }
    }

    /**
     * Geocode a city name to lat/lon using Open-Meteo's geocoding API.
     * PRIVACY: Only the user-entered city name is sent. The language
     * parameter uses the device's current locale so city names appear
     * in the user's own language in the UI — no user identifier is sent.
     */
    private fun geocode(query: String): Triple<Double, Double, String>? {
        val q = URLEncoder.encode(query, "UTF-8")
        val lang = applicationContext.resources.configuration.locales[0]
            .language.ifBlank { "en" }
        val url = URL("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=$lang&format=json")
        val json = getJson(url) ?: return null

        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null

        val r = results.getJSONObject(0)
        val lat = r.optDouble("latitude", Double.NaN)
        val lon = r.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null

        val name = r.optString("name", query)

        return Triple(lat, lon, name)
    }

    /**
     * Fetch today's forecast (min/max temperature, weather code) from Open-Meteo.
     * PRIVACY: Only lat/lon coordinates are sent — no user identifier.
     */
    private fun fetchTodayForecast(lat: Double, lon: Double): Triple<Int, Int, Int>? {
        val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_DATE)

        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$lat&longitude=$lon" +
                    "&daily=temperature_2m_max,temperature_2m_min,weathercode" +
                    "&timezone=Europe%2FBerlin" +
                    "&start_date=$today&end_date=$today"
        )

        val json = getJson(url) ?: return null
        val daily = json.optJSONObject("daily") ?: return null

        val tMaxArr = daily.optJSONArray("temperature_2m_max") ?: return null
        val tMinArr = daily.optJSONArray("temperature_2m_min") ?: return null
        val codeArr = daily.optJSONArray("weathercode") ?: return null

        val tMax = tMaxArr.optDouble(0, Double.NaN)
        val tMin = tMinArr.optDouble(0, Double.NaN)
        val code = codeArr.optInt(0, -1)
        if (tMax.isNaN() || tMin.isNaN() || code < 0) return null

        return Triple(tMin.toInt(), tMax.toInt(), code)
    }

    /**
     * Simple HTTPS GET helper — no cookies, no custom headers, no auth.
     */
    private fun getJson(url: URL): JSONObject? {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 7000
            readTimeout = 7000
            requestMethod = "GET"
        }
        return try {
            val body = conn.inputStream.bufferedReader().readText()
            JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Fetches city-name suggestions for the location input field.
 *
 * PRIVACY: Uses Open-Meteo's geocoding API (same service as the weather
 * worker). No separate third-party service is contacted.
 *
 * NOTE: Unlike the main doWork() above, this function does NOT check
 * getOwnAodWeatherEnabled() — by design. The city input field is only
 * shown in the weather-feature setup dialog, which is itself only
 * reachable AFTER the user has enabled the weather feature. The feature
 * gate is enforced UI-side; this function cannot be reached otherwise.
 *
 * What is sent: only the partial text the user is currently typing
 * and the device locale language (e.g. "de", "en") so city names
 * appear in the user's own language.
 * What is NOT sent: no device ID, no user identifier, no other data.
 *
 * @param ctx   Android context (used only to read the current locale).
 * @param query The text the user has typed so far (min. 3 characters).
 * @return A list of city-name suggestions (with country/region where available).
 */
suspend fun fetchLocationSuggestions(ctx: Context, query: String): List<String> {
    if (query.length < 3) return emptyList()
    return withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val lang = ctx.resources.configuration.locales[0]
                .language.ifBlank { "en" }
            val url = URL(
                "https://geocoding-api.open-meteo.com/v1/search" +
                        "?name=$q&count=10&language=$lang&format=json"
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }

            val body = try {
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }

            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: return@withContext emptyList()

            val suggestions = mutableListOf<String>()
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val name = r.optString("name", "").trim()
                if (name.isEmpty()) continue

                // Add country/region for disambiguation (e.g. "Berlin, Germany")
                val country = r.optString("country", "").trim()
                val admin = r.optString("admin1", "").trim()

                val label = when {
                    admin.isNotEmpty() && country.isNotEmpty() -> "$name, $admin, $country"
                    country.isNotEmpty() -> "$name, $country"
                    else -> name
                }
                if (!suggestions.contains(label)) suggestions.add(label)
            }
            suggestions
        } catch (_: Exception) {
            emptyList()
        }
    }
}
