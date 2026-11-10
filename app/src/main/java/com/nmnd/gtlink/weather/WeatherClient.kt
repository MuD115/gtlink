package com.nmnd.gtlink.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.math.roundToInt

class WeatherClient(private val http: OkHttpClient = OkHttpClient()) {

    suspend fun fetch(latitude: Double, longitude: Double, locationName: String): WeatherReport {
        val url = FORECAST_URL.toHttpUrl().newBuilder()
            .addQueryParameter("latitude", latitude.toString())
            .addQueryParameter("longitude", longitude.toString())
            .addQueryParameter(
                "current",
                "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code," +
                    "wind_speed_10m,wind_direction_10m,uv_index",
            )
            .addQueryParameter("hourly", "temperature_2m,weather_code,precipitation_probability,uv_index")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("timeformat", "unixtime")
            .addQueryParameter("forecast_days", "7")
            .build()
        val body = get(url.toString())
        return parseForecast(body, locationName, System.currentTimeMillis() / 1000)
    }

    suspend fun searchCity(query: String): List<City> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val url = GEOCODING_URL.toHttpUrl().newBuilder()
            .addQueryParameter("name", trimmed)
            .addQueryParameter("count", "8")
            .addQueryParameter("language", "en")
            .addQueryParameter("format", "json")
            .build()
        return parseCities(get(url.toString()))
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                val reason = errorReason(text) ?: response.message.ifBlank { "request failed" }
                throw IOException("Weather service error (HTTP ${response.code}): $reason")
            }
            text
        }
    }

    internal fun parseForecast(json: String, locationName: String, nowSec: Long): WeatherReport {
        val dto = try {
            lenientJson.decodeFromString<ForecastDto>(json)
        } catch (e: IllegalArgumentException) {
            throw IOException("Unexpected weather response: ${e.message}", e)
        }

        val daily = dto.daily?.toPoints().orEmpty().take(7)
        val today = daily.firstOrNull() ?: throw IOException("Weather response contained no daily forecast")
        val cur = dto.current

        return WeatherReport(
            locationName = locationName,
            latitude = dto.latitude,
            longitude = dto.longitude,
            currentTempC = cur.temperature,
            feelsLikeC = cur.apparentTemperature,
            todayMinC = today.minC,
            todayMaxC = today.maxC,
            wmoCode = cur.weatherCode,
            windSpeedKmh = cur.windSpeed,
            windDirectionDeg = cur.windDirection.roundToInt(),
            humidity = cur.humidity?.roundToInt(),
            uvIndex = cur.uvIndex,
            observedAtSec = cur.time ?: nowSec,
            hourly = dto.hourly?.toPoints(nowSec).orEmpty(),
            daily = daily,
        )
    }

    internal fun parseCities(json: String): List<City> {
        val dto = try {
            lenientJson.decodeFromString<GeocodingDto>(json)
        } catch (e: IllegalArgumentException) {
            throw IOException("Unexpected search response: ${e.message}", e)
        }
        return dto.results.map {
            City(
                name = it.name,
                admin = it.admin1?.takeIf(String::isNotBlank),
                country = it.country?.takeIf(String::isNotBlank),
                latitude = it.latitude,
                longitude = it.longitude,
            )
        }
    }

    private fun errorReason(body: String): String? = runCatching {
        lenientJson.parseToJsonElement(body).jsonObject["reason"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private companion object {
        const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
        const val HOURLY_COUNT = 24
        const val HOUR_SEC = 3600L

        val lenientJson = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    private data class ForecastDto(
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val current: CurrentDto,
        val hourly: HourlyDto? = null,
        val daily: DailyDto? = null,
    )

    @Serializable
    private data class CurrentDto(
        val time: Long? = null,
        @SerialName("temperature_2m") val temperature: Double,
        @SerialName("apparent_temperature") val apparentTemperature: Double? = null,
        @SerialName("relative_humidity_2m") val humidity: Double? = null,
        @SerialName("weather_code") val weatherCode: Int,
        @SerialName("wind_speed_10m") val windSpeed: Double = 0.0,
        @SerialName("wind_direction_10m") val windDirection: Double = 0.0,
        @SerialName("uv_index") val uvIndex: Double? = null,
    )

    @Serializable
    private data class HourlyDto(
        val time: List<Long> = emptyList(),
        @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
        @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
        @SerialName("precipitation_probability") val precipitationProbability: List<Double?> = emptyList(),
        @SerialName("uv_index") val uvIndex: List<Double?> = emptyList(),
    ) {
        // Starts at the hour containing nowSec; hourly stamps may sit on :30 for half-hour zones.
        fun toPoints(nowSec: Long): List<HourlyPoint> {
            val start = time.indexOfFirst { it + HOUR_SEC > nowSec }
            if (start < 0) return emptyList()
            val end = minOf(start + HOURLY_COUNT, time.size)
            return (start until end).mapNotNull { i ->
                val temp = temperature.getOrNull(i) ?: return@mapNotNull null
                val code = weatherCode.getOrNull(i) ?: return@mapNotNull null
                HourlyPoint(
                    timeSec = time[i],
                    tempC = temp,
                    wmoCode = code,
                    precipitationProbability = precipitationProbability.getOrNull(i)?.roundToInt(),
                    uvIndex = uvIndex.getOrNull(i),
                )
            }
        }
    }

    @Serializable
    private data class DailyDto(
        val time: List<Long> = emptyList(),
        @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
        @SerialName("temperature_2m_max") val max: List<Double?> = emptyList(),
        @SerialName("temperature_2m_min") val min: List<Double?> = emptyList(),
        val sunrise: List<Long?> = emptyList(),
        val sunset: List<Long?> = emptyList(),
    ) {
        fun toPoints(): List<DailyPoint> = time.indices.mapNotNull { i ->
            val hi = max.getOrNull(i) ?: return@mapNotNull null
            val lo = min.getOrNull(i) ?: return@mapNotNull null
            val code = weatherCode.getOrNull(i) ?: return@mapNotNull null
            DailyPoint(
                dateSec = time[i],
                minC = lo,
                maxC = hi,
                wmoCode = code,
                sunriseSec = sunrise.getOrNull(i),
                sunsetSec = sunset.getOrNull(i),
            )
        }
    }

    @Serializable
    private data class GeocodingDto(val results: List<CityDto> = emptyList())

    @Serializable
    private data class CityDto(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val country: String? = null,
        val admin1: String? = null,
    )
}
