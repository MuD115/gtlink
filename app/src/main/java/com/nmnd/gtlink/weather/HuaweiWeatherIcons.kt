package com.nmnd.gtlink.weather

object HuaweiWeatherIcons {
    const val SUNNY: Byte = 0x00
    const val CLOUDY: Byte = 0x01
    const val OVERCAST: Byte = 0x02
    const val SHOWERS: Byte = 0x03
    const val THUNDERSTORMS: Byte = 0x04
    const val THUNDER_AND_HAIL: Byte = 0x05
    const val SLEET: Byte = 0x06
    const val LIGHT_RAIN: Byte = 0x07
    const val RAIN: Byte = 0x08
    const val HEAVY_RAIN: Byte = 0x09
    const val RAIN_STORM: Byte = 0x0a
    const val SNOW_FLURRIES: Byte = 0x0d
    const val LIGHT_SNOW: Byte = 0x0e
    const val SNOW: Byte = 0x0f
    const val HEAVY_SNOW: Byte = 0x10
    const val SNOWSTORMS: Byte = 0x11
    const val FOG: Byte = 0x12
    const val FREEZING_RAIN: Byte = 0x13
    const val DUST_STORM: Byte = 0x14
    const val DUST: Byte = 0x1d
    const val SAND: Byte = 0x1e
    const val WINDY: Byte = 0x23
    const val HAZY: Byte = 0x35
    const val UNKNOWN: Byte = 0x63

    fun fromWmo(code: Int): Byte = when (code) {
        0 -> SUNNY
        1, 2 -> CLOUDY
        3 -> OVERCAST
        45, 48 -> FOG
        51, 53, 55 -> LIGHT_RAIN
        56, 57, 66, 67 -> FREEZING_RAIN
        61 -> LIGHT_RAIN
        63 -> RAIN
        65 -> HEAVY_RAIN
        71 -> LIGHT_SNOW
        73 -> SNOW
        75 -> HEAVY_SNOW
        77 -> SNOW_FLURRIES
        80, 81 -> SHOWERS
        82 -> RAIN_STORM
        85 -> LIGHT_SNOW
        86 -> HEAVY_SNOW
        95 -> THUNDERSTORMS
        96, 99 -> THUNDER_AND_HAIL
        else -> UNKNOWN
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80, 81 -> "Rain showers"
        82 -> "Violent rain showers"
        85 -> "Light snow showers"
        86 -> "Heavy snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }

    /** Beaufort scale (0-12) from a wind speed in km/h. */
    fun beaufort(kmh: Double): Int {
        if (kmh.isNaN()) return 0
        return when {
            kmh < 1.0 -> 0
            kmh < 6.0 -> 1
            kmh < 12.0 -> 2
            kmh < 20.0 -> 3
            kmh < 29.0 -> 4
            kmh < 39.0 -> 5
            kmh < 50.0 -> 6
            kmh < 62.0 -> 7
            kmh < 75.0 -> 8
            kmh < 89.0 -> 9
            kmh < 103.0 -> 10
            kmh < 118.0 -> 11
            else -> 12
        }
    }
}
