package com.calendarviewbox.data

import android.content.Context
import com.calendarviewbox.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class CalendarMode(val label: String) {
    WEEK("Week"),
    TWO_WEEKS("2 weeks"),
    MONTH("Month"),
    AGENDA("Agenda"),
}

fun mondayOf(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** First Monday shown in the month grid, and how many week rows the month needs. */
fun monthGrid(today: LocalDate): Pair<LocalDate, Int> {
    val first = today.withDayOfMonth(1)
    val start = mondayOf(first)
    val days = ChronoUnit.DAYS.between(start, first).toInt() + today.lengthOfMonth()
    return start to (days + 6) / 7
}

/** Date range [from, toExclusive) each view needs events for. */
fun CalendarMode.range(today: LocalDate): Pair<LocalDate, LocalDate> = when (this) {
    // Week and 2 weeks always start today, so no row is spent on days already gone.
    CalendarMode.WEEK -> today to today.plusDays(7)
    CalendarMode.TWO_WEEKS -> today to today.plusDays(14)
    CalendarMode.MONTH -> monthGrid(today).let { (start, weeks) -> start to start.plusDays(weeks * 7L) }
    CalendarMode.AGENDA -> today to today.plusDays(14)
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("board", Context.MODE_PRIVATE)

    /** Falls back to the value in local.properties when nothing was typed in Settings. */
    var todoistToken: String
        get() = sp.getString("todoist_token", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.TODOIST_TOKEN
        set(value) = sp.edit().putString("todoist_token", value.trim()).apply()

    var todoistProject: String
        get() = sp.getString("todoist_project", null) ?: BuildConfig.TODOIST_PROJECT
        set(value) = sp.edit().putString("todoist_project", value.trim()).apply()

    /** Blank hides the Groceries tab. */
    var groceriesProject: String
        get() = sp.getString("groceries_project", null) ?: DEFAULT_GROCERIES_PROJECT
        set(value) = sp.edit().putString("groceries_project", value.trim()).apply()

    /** One-tap items in the Groceries add panel. */
    var staples: List<String>
        get() = (sp.getString("staples", null) ?: DEFAULT_STAPLES)
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = sp.edit().putString("staples", value.joinToString(", ")).apply()

    var mode: CalendarMode
        get() = runCatching { CalendarMode.valueOf(sp.getString("mode", null) ?: "WEEK") }
            .getOrDefault(CalendarMode.WEEK)
        set(value) = sp.edit().putString("mode", value.name).apply()

    /** False until the user ticks or unticks a calendar. Until then every synced calendar shows. */
    var calendarsConfigured: Boolean
        get() = sp.getBoolean("calendars_configured", false)
        set(value) = sp.edit().putBoolean("calendars_configured", value).apply()

    var selectedCalendarIds: Set<Long>
        get() = sp.getStringSet("calendars", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        set(value) = sp.edit().putStringSet("calendars", value.map { it.toString() }.toSet()).apply()

    /** Every calendar id seen so far. New ones (e.g. from a just-added account) get ticked automatically. */
    var knownCalendarIds: Set<Long>
        get() = sp.getStringSet("known_calendars", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        set(value) = sp.edit().putStringSet("known_calendars", value.map { it.toString() }.toSet()).apply()

    fun styleFor(calendarId: Long): Int? = sp.getInt("style_$calendarId", -1).takeIf { it >= 0 }
    fun setStyle(calendarId: Long, style: Int) = sp.edit().putInt("style_$calendarId", style).apply()

    var textScale: Float
        get() = sp.getFloat("text_scale", 1f)
        set(value) = sp.edit().putFloat("text_scale", value).apply()

    // ---- weather ----

    var weatherPlace: String
        get() = sp.getString("weather_place", null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.WEATHER_PLACE.ifBlank { DEFAULT_WEATHER_PLACE }
        set(value) = sp.edit().putString("weather_place", value.trim()).apply()

    /** The place found for the typed text, so the search only runs when the text changes. */
    fun cachedPlace(query: String): Place? {
        if (sp.getString("place_query", null) != query) return null
        val name = sp.getString("place_name", null) ?: return null
        return Place(
            name = name,
            region = sp.getString("place_region", "") ?: "",
            lat = sp.getString("place_lat", null)?.toDoubleOrNull() ?: return null,
            lon = sp.getString("place_lon", null)?.toDoubleOrNull() ?: return null,
        )
    }

    fun savePlace(query: String, place: Place) = sp.edit()
        .putString("place_query", query)
        .putString("place_name", place.name)
        .putString("place_region", place.region)
        .putString("place_lat", place.lat.toString())
        .putString("place_lon", place.lon.toString())
        .apply()

    fun saveWeather(w: Weather) = sp.edit().putString("weather_json", WeatherClient.toJson(w)).apply()
    fun loadWeather(): Weather? = sp.getString("weather_json", null)?.let { WeatherClient.fromJson(it) }

    // ---- last good to-do list, shown after a restart while offline ----

    fun saveTasks(list: String, tasks: List<TodoTask>, at: Long) {
        val arr = JSONArray()
        tasks.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("content", t.content)
                    .put("parent", t.parentId ?: JSONObject.NULL)
                    .put("order", t.order)
                    .put("due", t.due?.toString() ?: JSONObject.NULL)
                    .put("time", t.dueTime?.toString() ?: JSONObject.NULL)
                    .put("recurring", t.isRecurring)
                    .put("section", t.sectionId ?: JSONObject.NULL)
            )
        }
        sp.edit().putString("tasks_json_$list", arr.toString()).putLong("tasks_at_$list", at).apply()
    }

    fun loadTasks(list: String): Pair<List<TodoTask>, Long>? = runCatching {
        val raw = sp.getString("tasks_json_$list", null) ?: return null
        val arr = JSONArray(raw)
        val tasks = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TodoTask(
                id = o.getString("id"),
                content = o.getString("content"),
                parentId = if (o.isNull("parent")) null else o.getString("parent"),
                order = o.optInt("order"),
                due = if (o.isNull("due")) null else LocalDate.parse(o.getString("due")),
                dueTime = if (o.isNull("time")) null else LocalTime.parse(o.getString("time")),
                isRecurring = o.optBoolean("recurring"),
                sectionId = if (!o.has("section") || o.isNull("section")) null else o.getString("section"),
            )
        }
        tasks to sp.getLong("tasks_at_$list", 0L)
    }.getOrNull()

    fun saveSections(list: String, sections: List<TodoSection>) {
        val arr = JSONArray()
        sections.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("order", it.order)) }
        sp.edit().putString("sections_json_$list", arr.toString()).apply()
    }

    fun loadSections(list: String): List<TodoSection> = runCatching {
        val arr = JSONArray(sp.getString("sections_json_$list", null) ?: return emptyList())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            TodoSection(o.getString("id"), o.getString("name"), o.optInt("order"))
        }
    }.getOrDefault(emptyList())

    companion object {
        const val DEFAULT_WEATHER_PLACE = "Sydney, NSW"
        const val DEFAULT_GROCERIES_PROJECT = "Groceries"
        const val DEFAULT_STAPLES = "Milk, Bread, Eggs, Bananas, Butter, Cheese, Coffee, Toilet paper"
    }
}
