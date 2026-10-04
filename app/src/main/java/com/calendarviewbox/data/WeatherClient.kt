package com.calendarviewbox.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

data class Place(val name: String, val region: String, val lat: Double, val lon: Double) {
    val label: String get() = if (region.isBlank()) name else "$name, $region"
}

data class DayWeather(val date: LocalDate, val code: Int, val max: Int, val min: Int, val rainChance: Int?)

data class Weather(
    val currentTemp: Int?,
    val currentCode: Int?,
    val days: List<DayWeather>,
    val fetchedAt: Long,
)

/** Simple sky types, drawn as greyscale icons. */
enum class Sky { SUN, PARTLY, CLOUD, FOG, RAIN, STORM, SNOW }

/** WMO weather codes, as used by Open-Meteo. */
fun skyOf(code: Int): Sky = when (code) {
    0, 1 -> Sky.SUN
    2 -> Sky.PARTLY
    3 -> Sky.CLOUD
    45, 48 -> Sky.FOG
    in 51..67, in 80..82 -> Sky.RAIN
    in 71..77, 85, 86 -> Sky.SNOW
    in 95..99 -> Sky.STORM
    else -> Sky.CLOUD
}

fun describeWeather(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mostly sunny"
    2 -> "Partly cloudy"
    3 -> "Cloudy"
    45, 48 -> "Fog"
    51, 53, 55, 56, 57 -> "Drizzle"
    61 -> "Light rain"
    63, 66 -> "Rain"
    65, 67 -> "Heavy rain"
    71, 73, 75, 77, 85, 86 -> "Snow"
    80 -> "Light showers"
    81 -> "Showers"
    82 -> "Heavy showers"
    95 -> "Thunderstorm"
    96, 99 -> "Storm with hail"
    else -> "Unknown"
}

private val AU_STATES = mapOf(
    "NSW" to "New South Wales", "VIC" to "Victoria", "QLD" to "Queensland",
    "WA" to "Western Australia", "SA" to "South Australia", "TAS" to "Tasmania",
    "ACT" to "Australian Capital Territory", "NT" to "Northern Territory",
)

/** Open-Meteo: free, no account or key (https://open-meteo.com). */
object WeatherClient {

    /** "Burwood, NSW" -> searches "Burwood", prefers a match in New South Wales, then anywhere in Australia. */
    suspend fun findPlace(query: String): Place? = withContext(Dispatchers.IO) {
        val parts = query.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return@withContext null
        val name = parts.first()
        val hintRaw = parts.drop(1).joinToString(" ").trim()
        val hint = (AU_STATES[hintRaw.uppercase()] ?: hintRaw).lowercase()

        val url = "https://geocoding-api.open-meteo.com/v1/search?name=${urlEncode(name)}&count=10&language=en&format=json"
        val (code, body) = httpRequest("GET", url)
        if (code !in 200..299) throw HttpStatusException(code, "Place search failed ($code)")
        val arr = JSONObject(body).optJSONArray("results") ?: JSONArray()
        val results = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

        val pick = results.firstOrNull { hint.isNotEmpty() &&
                (it.optString("admin1").lowercase().contains(hint) || it.optString("country").lowercase().contains(hint)) }
            ?: results.firstOrNull { it.optString("country_code") == "AU" }
            ?: results.firstOrNull()
        pick?.let { Place(it.optString("name"), it.optString("admin1"), it.getDouble("latitude"), it.getDouble("longitude")) }
    }

    suspend fun forecast(place: Place): Weather = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
            "&current=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=6"
        val (code, body) = httpRequest("GET", url)
        if (code !in 200..299) throw HttpStatusException(code, "Weather request failed ($code)")
        parseForecast(JSONObject(body), System.currentTimeMillis())
    }

    fun parseForecast(o: JSONObject, fetchedAt: Long): Weather {
        val current = o.optJSONObject("current")
        val daily = o.getJSONObject("daily")
        val times = daily.getJSONArray("time")
        val codes = daily.optJSONArray("weather_code")
        val maxes = daily.optJSONArray("temperature_2m_max")
        val mins = daily.optJSONArray("temperature_2m_min")
        val rain = daily.optJSONArray("precipitation_probability_max")
        val days = (0 until times.length()).map { i ->
            DayWeather(
                date = LocalDate.parse(times.getString(i)),
                code = codes?.optInt(i, 3) ?: 3,
                max = (maxes?.optDouble(i) ?: 0.0).roundToInt(),
                min = (mins?.optDouble(i) ?: 0.0).roundToInt(),
                rainChance = if (rain == null || rain.isNull(i)) null else rain.optInt(i),
            )
        }
        return Weather(
            currentTemp = current?.takeIf { it.has("temperature_2m") }?.optDouble("temperature_2m")?.roundToInt(),
            currentCode = current?.takeIf { it.has("weather_code") }?.optInt("weather_code"),
            days = days,
            fetchedAt = fetchedAt,
        )
    }

    /** Stores the raw shape so a cached forecast can be shown after a restart. */
    fun toJson(w: Weather): String {
        val daily = JSONObject()
            .put("time", JSONArray(w.days.map { it.date.toString() }))
            .put("weather_code", JSONArray(w.days.map { it.code }))
            .put("temperature_2m_max", JSONArray(w.days.map { it.max }))
            .put("temperature_2m_min", JSONArray(w.days.map { it.min }))
            .put("precipitation_probability_max", JSONArray(w.days.map { it.rainChance ?: JSONObject.NULL }))
        val o = JSONObject().put("daily", daily).put("fetched_at", w.fetchedAt)
        if (w.currentTemp != null) {
            o.put("current", JSONObject().put("temperature_2m", w.currentTemp).put("weather_code", w.currentCode ?: 3))
        }
        return o.toString()
    }

    fun fromJson(s: String): Weather? = runCatching {
        val o = JSONObject(s)
        parseForecast(o, o.optLong("fetched_at", 0L))
    }.getOrNull()
}
