package com.nmnd.gtlink.weather

data class HourlyPoint(
    val timeSec: Long,
    val tempC: Double,
    val wmoCode: Int,
    val precipitationProbability: Int?,
    val uvIndex: Double?,
)

data class DailyPoint(
    val dateSec: Long,
    val minC: Double,
    val maxC: Double,
    val wmoCode: Int,
    val sunriseSec: Long?,
    val sunsetSec: Long?,
)

data class WeatherReport(
    val locationName: String,
    val latitude: Double,
    val longitude: Double,
    val currentTempC: Double,
    val feelsLikeC: Double?,
    val todayMinC: Double,
    val todayMaxC: Double,
    val wmoCode: Int,
    val windSpeedKmh: Double,
    val windDirectionDeg: Int,
    val humidity: Int?,
    val uvIndex: Double?,
    val observedAtSec: Long,
    /** Next 24 hours starting from the current hour. */
    val hourly: List<HourlyPoint>,
    /** Today plus the following days (up to 7 entries). */
    val daily: List<DailyPoint>,
)

data class City(
    val name: String,
    val admin: String?,
    val country: String?,
    val latitude: Double,
    val longitude: Double,
)
