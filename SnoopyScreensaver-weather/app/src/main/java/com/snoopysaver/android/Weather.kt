package com.snoopysaver.android

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object WeatherState {
    @Volatile var conditions: Set<String> = emptySet()
    @Volatile var sunriseMillis: Long? = null
    @Volatile var sunsetMillis: Long? = null

    fun apply(snapshot: WeatherSnapshot) {
        conditions = snapshot.conditions
        sunriseMillis = snapshot.sunriseMillis
        sunsetMillis = snapshot.sunsetMillis
    }

    fun clear() {
        conditions = emptySet()
        sunriseMillis = null
        sunsetMillis = null
    }
}

data class WeatherPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val timeZone: String?,
)

data class WeatherSnapshot(
    val conditions: Set<String>,
    val observedAt: Long,
    val expiresAt: Long,
    val sunriseMillis: Long?,
    val sunsetMillis: Long?,
    val locationName: String,
) {
    fun summary(): String {
        val labels = conditions.map { weatherLabel(it) }.distinct().joinToString("、")
        return if (labels.isEmpty()) locationName else "$locationName · $labels"
    }
}

fun weatherLabel(token: String): String = when (token) {
    "clear", "sunny" -> "晴"
    "cloudy" -> "阴"
    "foggy" -> "雾"
    "rainy" -> "雨"
    "icy" -> "冻雨"
    "snowy" -> "雪"
    "stormy" -> "雷暴"
    "windy" -> "大风"
    else -> token
}

object WeatherConditions {
    fun fromOpenMeteo(wmoCode: Int, windSpeed: Double, isDay: Boolean): Set<String> {
        val values = when (wmoCode) {
            0, 1 -> if (isDay) mutableSetOf("clear", "sunny") else mutableSetOf("clear")
            2, 3 -> mutableSetOf("cloudy")
            45, 48 -> mutableSetOf("foggy")
            in 51..55, in 61..65, in 80..82 -> mutableSetOf("rainy")
            56, 57, 66, 67 -> mutableSetOf("icy", "rainy")
            in 71..77, 85, 86 -> mutableSetOf("snowy")
            in 95..99 -> mutableSetOf("stormy")
            else -> mutableSetOf()
        }
        if (windSpeed >= 29.0) values += "windy"
        return values
    }
}

object WeatherClient {
    fun resolve(city: String): WeatherPlace {
        val name = city.trim()
        if (name.length < 2) error("请填写至少两个字的城市。")
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            URLEncoder.encode(name, "UTF-8") + "&count=1&language=zh&format=json"
        val json = JSONObject(get(url))
        val results = json.optJSONArray("results") ?: error("没有找到这个城市。")
        val place = results.optJSONObject(0) ?: error("没有找到这个城市。")
        val parts = listOf(place.optString("name"), place.optString("admin1"), place.optString("country"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return WeatherPlace(
            name = parts.joinToString(" · ").ifEmpty { name },
            latitude = place.getDouble("latitude"),
            longitude = place.getDouble("longitude"),
            timeZone = place.optString("timezone").ifEmpty { null },
        )
    }

    fun fetch(place: WeatherPlace): WeatherSnapshot {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}" +
            "&longitude=${place.longitude}" +
            "&current=weather_code,wind_speed_10m,is_day" +
            "&daily=sunrise,sunset&timezone=auto&forecast_days=1"
        val json = JSONObject(get(url))
        val current = json.optJSONObject("current") ?: error("天气服务返回了无法识别的数据。")
        val zone = TimeZone.getTimeZone(json.optString("timezone").ifEmpty { place.timeZone ?: "UTC" })
        val daily = json.optJSONObject("daily")
        val now = System.currentTimeMillis()
        return WeatherSnapshot(
            conditions = WeatherConditions.fromOpenMeteo(
                wmoCode = current.optInt("weather_code"),
                windSpeed = current.optDouble("wind_speed_10m", 0.0),
                isDay = current.optInt("is_day", 1) != 0,
            ),
            observedAt = parseLocal(current.optString("time"), zone) ?: now,
            expiresAt = now + 60 * 60 * 1000,
            sunriseMillis = parseLocal(daily?.optJSONArray("sunrise")?.optString(0), zone),
            sunsetMillis = parseLocal(daily?.optJSONArray("sunset")?.optString(0), zone),
            locationName = place.name,
        )
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.requestMethod = "GET"
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("天气服务暂时不可用。")
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun parseLocal(value: String?, zone: TimeZone): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
            format.timeZone = zone
            format.parse(value)?.time
        } catch (_: Exception) {
            null
        }
    }
}

object WeatherStore {
    private const val PREFS = "snoopy_weather"
    private const val ENABLED = "weather_enabled"
    private const val PAUSED = "weather_paused"
    private const val CITY = "weather_city"
    private const val LAT = "weather_lat"
    private const val LON = "weather_lon"
    private const val ZONE = "weather_zone"
    private const val PLACE = "weather_place"
    private const val CONDITIONS = "weather_conditions"
    private const val EXPIRES = "weather_expires"
    private const val SUNRISE = "weather_sunrise"
    private const val SUNSET = "weather_sunset"

    fun load(context: Context) {
        val prefs = prefs(context)
        if (!enabled(context)) {
            WeatherState.clear()
            return
        }
        val conditions = prefs.getString(CONDITIONS, "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        WeatherState.conditions = conditions
        WeatherState.sunriseMillis = prefs.getLong(SUNRISE, 0L).takeIf { it > 0L }
        WeatherState.sunsetMillis = prefs.getLong(SUNSET, 0L).takeIf { it > 0L }
    }

    fun enabled(context: Context): Boolean {
        val prefs = prefs(context)
        val city = prefs.getString(CITY, "").orEmpty()
        if (city.isEmpty()) return false
        return !prefs.getBoolean(PAUSED, false)
    }

    fun city(context: Context): String {
        return prefs(context).getString(CITY, "").orEmpty()
    }

    fun needsRefresh(context: Context): Boolean {
        val prefs = prefs(context)
        if (!enabled(context)) return false
        return System.currentTimeMillis() >= prefs.getLong(EXPIRES, 0L)
    }

    fun place(context: Context): WeatherPlace? {
        val prefs = prefs(context)
        if (!prefs.contains(LAT) || !prefs.contains(LON)) return null
        return WeatherPlace(
            name = prefs.getString(PLACE, "").orEmpty(),
            latitude = Double.fromBits(prefs.getLong(LAT, 0L)),
            longitude = Double.fromBits(prefs.getLong(LON, 0L)),
            timeZone = prefs.getString(ZONE, null),
        )
    }

    fun save(context: Context, city: String, place: WeatherPlace, snapshot: WeatherSnapshot) {
        prefs(context).edit()
            .putBoolean(ENABLED, true)
            .putBoolean(PAUSED, false)
            .putString(CITY, city.trim())
            .putString(PLACE, snapshot.locationName)
            .putLong(LAT, place.latitude.toBits())
            .putLong(LON, place.longitude.toBits())
            .putString(ZONE, place.timeZone)
            .putString(CONDITIONS, snapshot.conditions.joinToString(","))
            .putLong(EXPIRES, snapshot.expiresAt)
            .putLong(SUNRISE, snapshot.sunriseMillis ?: 0L)
            .putLong(SUNSET, snapshot.sunsetMillis ?: 0L)
            .commit()
        WeatherState.apply(snapshot)
        Log.i(TAG, "weather ${snapshot.summary()}")
    }

    fun setPaused(context: Context, paused: Boolean) {
        prefs(context).edit()
            .putBoolean(ENABLED, !paused)
            .putBoolean(PAUSED, paused)
            .commit()
        if (paused) {
            WeatherState.clear()
        } else {
            load(context)
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TAG = "SnoopySaver"
}
