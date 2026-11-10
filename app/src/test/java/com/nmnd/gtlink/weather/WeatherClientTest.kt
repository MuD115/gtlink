package com.nmnd.gtlink.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class WeatherClientTest {

    private val client = WeatherClient()

    // 2023-11-14 22:13:20 UTC; the containing hour starts at 1699999200.
    private val nowSec = 1_700_000_000L
    private val hourStart = 1_699_999_200L

    private fun forecastJson(
        hourlyCount: Int = 36,
        hourlyFirst: Long = hourStart - 3 * 3600,
    ): String {
        val times = (0 until hourlyCount).joinToString(",") { (hourlyFirst + it * 3600L).toString() }
        val temps = (0 until hourlyCount).joinToString(",") { (10.0 + it).toString() }
        val codes = (0 until hourlyCount).joinToString(",") { (it % 4).toString() }
        val precip = (0 until hourlyCount).joinToString(",") { if (it == 5) "null" else (it * 2).toString() }
        val uv = (0 until hourlyCount).joinToString(",") { "0.5" }
        return """
        {
          "latitude": 52.52, "longitude": 13.41, "generationtime_ms": 0.1,
          "utc_offset_seconds": 3600, "timezone": "Europe/Berlin",
          "current_units": {"temperature_2m": "°C"},
          "current": {
            "time": 1699999500, "interval": 900,
            "temperature_2m": 12.4, "apparent_temperature": 10.1,
            "relative_humidity_2m": 81, "weather_code": 61,
            "wind_speed_10m": 14.6, "wind_direction_10m": 247, "uv_index": 0.0
          },
          "hourly": {
            "time": [$times],
            "temperature_2m": [$temps],
            "weather_code": [$codes],
            "precipitation_probability": [$precip],
            "uv_index": [$uv]
          },
          "daily": {
            "time": [1699920000,1700006400,1700092800,1700179200,1700265600,1700352000,1700438400],
            "weather_code": [61,3,0,2,95,71,45],
            "temperature_2m_max": [13.1,12.0,11.5,10.0,9.5,3.2,4.0],
            "temperature_2m_min": [8.2,7.0,5.5,4.0,3.5,-1.2,0.0],
            "sunrise": [1699942800,1700029300,null,1700202200,1700288600,1700375000,1700461400],
            "sunset": [1699974000,1700060000,1700146000,1700232000,1700318000,1700404000,1700490000]
          }
        }
        """.trimIndent()
    }

    @Test
    fun parseForecast_readsCurrentConditions() {
        val r = client.parseForecast(forecastJson(), "Berlin", nowSec)
        assertEquals("Berlin", r.locationName)
        assertEquals(52.52, r.latitude, 1e-9)
        assertEquals(13.41, r.longitude, 1e-9)
        assertEquals(12.4, r.currentTempC, 1e-9)
        assertEquals(10.1, r.feelsLikeC!!, 1e-9)
        assertEquals(61, r.wmoCode)
        assertEquals(14.6, r.windSpeedKmh, 1e-9)
        assertEquals(247, r.windDirectionDeg)
        assertEquals(81, r.humidity)
        assertEquals(0.0, r.uvIndex!!, 1e-9)
        assertEquals(1_699_999_500L, r.observedAtSec)
    }

    @Test
    fun parseForecast_todayRangeComesFromFirstDailyEntry() {
        val r = client.parseForecast(forecastJson(), "Berlin", nowSec)
        assertEquals(8.2, r.todayMinC, 1e-9)
        assertEquals(13.1, r.todayMaxC, 1e-9)
    }

    @Test
    fun parseForecast_dailyHasSevenDaysWithNullableSun() {
        val r = client.parseForecast(forecastJson(), "Berlin", nowSec)
        assertEquals(7, r.daily.size)
        assertEquals(1_699_920_000L, r.daily[0].dateSec)
        assertEquals(1_699_942_800L, r.daily[0].sunriseSec)
        assertEquals(1_699_974_000L, r.daily[0].sunsetSec)
        assertNull(r.daily[2].sunriseSec)
        assertEquals(-1.2, r.daily[5].minC, 1e-9)
        assertEquals(45, r.daily[6].wmoCode)
    }

    @Test
    fun parseForecast_hourlyStartsAtCurrentHourAndHas24Entries() {
        val r = client.parseForecast(forecastJson(), "Berlin", nowSec)
        assertEquals(24, r.hourly.size)
        assertEquals(hourStart, r.hourly.first().timeSec)
        assertEquals(13.0, r.hourly.first().tempC, 1e-9)
        assertEquals(hourStart + 23 * 3600L, r.hourly.last().timeSec)
        assertEquals(3, r.hourly.first().wmoCode)
        assertEquals(0.5, r.hourly.first().uvIndex!!, 1e-9)
    }

    @Test
    fun parseForecast_hourlyNullPrecipitationBecomesNull() {
        val r = client.parseForecast(forecastJson(), "Berlin", nowSec)
        // Source index 5 is null; it sits two entries after the current hour (index 3).
        assertEquals(6, r.hourly[0].precipitationProbability)
        assertNull(r.hourly[2].precipitationProbability)
        assertEquals(8, r.hourly[1].precipitationProbability)
    }

    @Test
    fun parseForecast_hourlyShorterThan24WhenDataRunsOut() {
        val r = client.parseForecast(forecastJson(hourlyCount = 10), "Berlin", nowSec)
        assertEquals(7, r.hourly.size)
        assertEquals(hourStart, r.hourly.first().timeSec)
    }

    @Test
    fun parseForecast_hourlyEmptyWhenAllInPast() {
        val r = client.parseForecast(forecastJson(hourlyCount = 5, hourlyFirst = hourStart - 20 * 3600L), "X", nowSec)
        assertTrue(r.hourly.isEmpty())
    }

    @Test
    fun parseForecast_halfHourOffsetZoneStillPicksContainingHour() {
        // Zone with +05:30 offset: hourly stamps fall on :30 UTC.
        // The stamp 1699997400 covers [1699997400, 1700001000), which contains nowSec.
        val containing = 1_699_997_400L
        val r = client.parseForecast(forecastJson(hourlyFirst = containing - 3 * 3600L), "Mumbai", nowSec)
        assertEquals(containing, r.hourly.first().timeSec)
    }

    @Test
    fun parseForecast_optionalCurrentFieldsMayBeMissing() {
        val json = """
        {"latitude":1.0,"longitude":2.0,
         "current":{"temperature_2m":5.5,"weather_code":0},
         "hourly":{"time":[],"temperature_2m":[],"weather_code":[]},
         "daily":{"time":[1699920000],"weather_code":[0],
                  "temperature_2m_max":[7.0],"temperature_2m_min":[1.0],
                  "sunrise":[],"sunset":[]}}
        """.trimIndent()
        val r = client.parseForecast(json, "Nowhere", nowSec)
        assertNull(r.feelsLikeC)
        assertNull(r.humidity)
        assertNull(r.uvIndex)
        assertEquals(nowSec, r.observedAtSec)
        assertTrue(r.hourly.isEmpty())
        assertNull(r.daily[0].sunriseSec)
    }

    @Test
    fun parseForecast_malformedJsonThrowsIOException() {
        try {
            client.parseForecast("""{"error":true,"reason":"bad"}""", "X", nowSec)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun parseCities_mapsFields() {
        val json = """
        {"results":[
          {"id":2950159,"name":"Berlin","latitude":52.52437,"longitude":13.41053,"elevation":74.0,
           "country_code":"DE","admin1":"Land Berlin","country":"Germany","timezone":"Europe/Berlin"},
          {"id":1,"name":"Berlin","latitude":44.46867,"longitude":-71.18508,"country":"United States","admin1":""},
          {"id":2,"name":"Lonely","latitude":1.5,"longitude":2.5}
        ],"generationtime_ms":0.5}
        """.trimIndent()
        val cities = client.parseCities(json)
        assertEquals(3, cities.size)
        assertEquals(City("Berlin", "Land Berlin", "Germany", 52.52437, 13.41053), cities[0])
        assertNull(cities[1].admin)
        assertEquals("United States", cities[1].country)
        assertEquals(-71.18508, cities[1].longitude, 1e-9)
        assertNull(cities[2].admin)
        assertNull(cities[2].country)
    }

    @Test
    fun parseCities_noResultsKeyYieldsEmptyList() {
        assertTrue(client.parseCities("""{"generationtime_ms":0.3}""").isEmpty())
    }

    @Test
    fun fromWmo_mapsKnownCodes() {
        val m = HuaweiWeatherIcons
        assertEquals(m.SUNNY, m.fromWmo(0))
        assertEquals(m.CLOUDY, m.fromWmo(1))
        assertEquals(m.CLOUDY, m.fromWmo(2))
        assertEquals(m.OVERCAST, m.fromWmo(3))
        assertEquals(m.FOG, m.fromWmo(45))
        assertEquals(m.FOG, m.fromWmo(48))
        assertEquals(m.LIGHT_RAIN, m.fromWmo(53))
        assertEquals(m.FREEZING_RAIN, m.fromWmo(56))
        assertEquals(m.FREEZING_RAIN, m.fromWmo(67))
        assertEquals(m.LIGHT_RAIN, m.fromWmo(61))
        assertEquals(m.RAIN, m.fromWmo(63))
        assertEquals(m.HEAVY_RAIN, m.fromWmo(65))
        assertEquals(m.LIGHT_SNOW, m.fromWmo(71))
        assertEquals(m.SNOW, m.fromWmo(73))
        assertEquals(m.HEAVY_SNOW, m.fromWmo(75))
        assertEquals(m.SNOW_FLURRIES, m.fromWmo(77))
        assertEquals(m.SHOWERS, m.fromWmo(81))
        assertEquals(m.RAIN_STORM, m.fromWmo(82))
        assertEquals(m.LIGHT_SNOW, m.fromWmo(85))
        assertEquals(m.HEAVY_SNOW, m.fromWmo(86))
        assertEquals(m.THUNDERSTORMS, m.fromWmo(95))
        assertEquals(m.THUNDER_AND_HAIL, m.fromWmo(96))
        assertEquals(m.THUNDER_AND_HAIL, m.fromWmo(99))
    }

    @Test
    fun fromWmo_unknownCodesAndRawByteValues() {
        assertEquals(HuaweiWeatherIcons.UNKNOWN, HuaweiWeatherIcons.fromWmo(42))
        assertEquals(HuaweiWeatherIcons.UNKNOWN, HuaweiWeatherIcons.fromWmo(-1))
        assertEquals(0x00.toByte(), HuaweiWeatherIcons.fromWmo(0))
        assertEquals(0x63.toByte(), HuaweiWeatherIcons.UNKNOWN)
        assertEquals(0x1d.toByte(), HuaweiWeatherIcons.DUST)
        assertEquals(0x35.toByte(), HuaweiWeatherIcons.HAZY)
    }

    @Test
    fun describe_returnsLabels() {
        assertEquals("Clear", HuaweiWeatherIcons.describe(0))
        assertEquals("Partly cloudy", HuaweiWeatherIcons.describe(2))
        assertEquals("Rain", HuaweiWeatherIcons.describe(63))
        assertEquals("Unknown", HuaweiWeatherIcons.describe(1234))
    }

    @Test
    fun beaufort_scaleBoundaries() {
        assertEquals(0, HuaweiWeatherIcons.beaufort(0.0))
        assertEquals(0, HuaweiWeatherIcons.beaufort(0.9))
        assertEquals(1, HuaweiWeatherIcons.beaufort(1.0))
        assertEquals(2, HuaweiWeatherIcons.beaufort(6.0))
        assertEquals(3, HuaweiWeatherIcons.beaufort(15.0))
        assertEquals(4, HuaweiWeatherIcons.beaufort(28.9))
        assertEquals(5, HuaweiWeatherIcons.beaufort(29.0))
        assertEquals(6, HuaweiWeatherIcons.beaufort(45.0))
        assertEquals(8, HuaweiWeatherIcons.beaufort(70.0))
        assertEquals(11, HuaweiWeatherIcons.beaufort(117.9))
        assertEquals(12, HuaweiWeatherIcons.beaufort(118.0))
        assertEquals(12, HuaweiWeatherIcons.beaufort(300.0))
        assertEquals(0, HuaweiWeatherIcons.beaufort(-5.0))
    }
}
