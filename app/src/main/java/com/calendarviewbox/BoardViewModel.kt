package com.calendarviewbox

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.calendarviewbox.data.CalendarInfo
import com.calendarviewbox.data.CalendarMode
import com.calendarviewbox.data.CalendarRepository
import com.calendarviewbox.data.EventItem
import com.calendarviewbox.data.Prefs
import com.calendarviewbox.data.TodoTask
import com.calendarviewbox.data.TodoistClient
import com.calendarviewbox.data.Weather
import com.calendarviewbox.data.WeatherClient
import com.calendarviewbox.data.isNetworkProblem
import com.calendarviewbox.data.range
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

const val STYLE_COUNT = 4

/** How long the connection must be gone before the offline note appears. */
const val OFFLINE_GRACE_MS = 60_000L

data class DisplayTask(val task: TodoTask, val depth: Int)

data class BoardState(
    val today: LocalDate = LocalDate.now(),
    val now: LocalTime = LocalTime.now().withSecond(0).withNano(0),
    val use24h: Boolean = false,
    val mode: CalendarMode = CalendarMode.WEEK,
    val hasCalendarPermission: Boolean = false,
    val calendars: List<CalendarInfo> = emptyList(),
    val shownCalendarIds: Set<Long> = emptySet(),
    val styles: Map<Long, Int> = emptyMap(),
    val events: List<EventItem> = emptyList(),
    val calendarError: String? = null,
    val tasks: List<DisplayTask> = emptyList(),
    val completingIds: Set<String> = emptySet(),
    val todoError: String? = null,
    val lastTodoSync: LocalTime? = null,
    val weather: Weather? = null,
    val weatherPlace: String? = null,
    val weatherError: String? = null,
    /** When the connection was first noticed missing. Null while online. */
    val offlineSinceMillis: Long? = null,
    val textScale: Float = 1f,
    val flashTick: Int = 0,
)

class BoardViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val calendarRepo = CalendarRepository(app)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    private val _state = MutableStateFlow(initialState(app))
    val state: StateFlow<BoardState> = _state.asStateFlow()

    private val todoMutex = Mutex()
    private val weatherMutex = Mutex()
    private val completing = mutableSetOf<String>()
    private var cachedProjectKey: String? = null
    private var cachedProjectId: String? = null
    private var calendarJob: Job? = null
    private var debounceJob: Job? = null
    private var lostJob: Job? = null
    private var observerRegistered = false
    private var networkCallbackRegistered = false

    private val calendarObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = scheduleCalendarReload()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModelScope.launch {
                lostJob?.cancel()
                // Connection is back: pull fresh data. A successful fetch clears the warning.
                withContext(Dispatchers.IO) { runCatching { calendarRepo.requestSync() } }
                refreshTodos()
                refreshWeather()
            }
        }

        override fun onLost(network: Network) {
            lostJob?.cancel()
            lostJob = viewModelScope.launch {
                // Wi-Fi often hands over between networks; only warn if it stays gone.
                delay(5_000)
                if (!hasInternet()) markOffline()
            }
        }
    }

    init {
        registerObserver()
        registerNetworkCallback()
        if (!hasInternet()) markOffline()
        reloadCalendar()
        viewModelScope.launch { clockLoop() }
        viewModelScope.launch { todoLoop() }
        viewModelScope.launch { weatherLoop() }
        viewModelScope.launch { flashLoop() }
    }

    private fun initialState(app: Application): BoardState {
        // Show the last saved list and forecast straight away, even before the first sync.
        val saved = prefs.loadTasks()
        return BoardState(
            mode = prefs.mode,
            textScale = prefs.textScale,
            use24h = DateFormat.is24HourFormat(app),
            hasCalendarPermission = hasCalendarPermission(),
            tasks = saved?.first?.let { orderTasks(it) } ?: emptyList(),
            lastTodoSync = saved?.second?.takeIf { it > 0 }?.let { millisToTime(it) },
            weather = prefs.loadWeather(),
            weatherPlace = prefs.cachedPlace(prefs.weatherPlace)?.label,
        )
    }

    override fun onCleared() {
        val app = getApplication<Application>()
        if (observerRegistered) app.contentResolver.unregisterContentObserver(calendarObserver)
        if (networkCallbackRegistered) runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
    }

    // ---- lifecycle hooks from the activity ----

    fun onResume() {
        _state.update { it.copy(use24h = DateFormat.is24HourFormat(getApplication<Application>())) }
        registerObserver()
        reloadCalendar()
        viewModelScope.launch { refreshTodos() }
    }

    fun onPermissionResult() {
        registerObserver()
        reloadCalendar()
    }

    // ---- user actions ----

    fun setMode(mode: CalendarMode) {
        prefs.mode = mode
        _state.update { it.copy(mode = mode) }
        reloadCalendar()
    }

    fun manualRefresh() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { calendarRepo.requestSync() } }
        reloadCalendar()
        viewModelScope.launch { refreshTodos() }
        viewModelScope.launch { refreshWeather() }
        flash()
    }

    fun completeTask(id: String) {
        if (!completing.add(id)) return
        _state.update { it.copy(completingIds = completing.toSet()) }
        viewModelScope.launch {
            try {
                TodoistClient(prefs.todoistToken).close(id)
                markOnline()
                delay(800) // leave the ticked state visible briefly
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isNetworkProblem(e)) markOffline()
                _state.update { it.copy(todoError = "Couldn't tick that off: ${e.message}") }
            }
            completing.remove(id)
            refreshTodos()
            _state.update { it.copy(completingIds = completing.toSet()) }
        }
    }

    /** Adds an item from the Boox keyboard. [onResult] gets null on success, or a message to show. */
    fun addTask(text: String, onResult: (String?) -> Unit) {
        val content = text.trim()
        if (content.isEmpty()) {
            onResult("Type something to add.")
            return
        }
        viewModelScope.launch {
            try {
                val token = prefs.todoistToken
                if (token.isBlank()) {
                    onResult("Add the Todoist token in Settings first.")
                    return@launch
                }
                val client = TodoistClient(token)
                val projectId = resolveProjectId(client)
                if (projectId == null) {
                    onResult("No matching Todoist project. Check the project name in Settings.")
                    return@launch
                }
                client.addTask(content, projectId)
                markOnline()
                onResult(null)
                refreshTodos()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isNetworkProblem(e)) {
                    markOffline()
                    onResult("Couldn't add it. Check the Boox is on Wi-Fi.")
                } else {
                    onResult("Couldn't add it: ${e.message}")
                }
            }
        }
    }

    fun currentToken(): String = prefs.todoistToken
    fun currentProject(): String = prefs.todoistProject
    fun currentWeatherPlace(): String = prefs.weatherPlace

    fun saveTodoist(token: String, project: String) {
        if (token.trim() != prefs.todoistToken) prefs.todoistToken = token
        if (project.trim() != prefs.todoistProject) prefs.todoistProject = project
        cachedProjectKey = null
        viewModelScope.launch { refreshTodos() }
    }

    fun saveWeatherPlace(place: String) {
        if (place.trim() == prefs.weatherPlace) return
        prefs.weatherPlace = place
        viewModelScope.launch { refreshWeather() }
    }

    fun setCalendarShown(calendarId: Long, shown: Boolean) {
        val current = _state.value.shownCalendarIds
        prefs.calendarsConfigured = true
        prefs.selectedCalendarIds = if (shown) current + calendarId else current - calendarId
        viewModelScope.launch {
            if (shown) withContext(Dispatchers.IO) { runCatching { calendarRepo.enableSync(calendarId) } }
            reloadCalendar()
        }
    }

    fun cycleStyle(calendarId: Long) {
        val next = ((_state.value.styles[calendarId] ?: 0) + 1) % STYLE_COUNT
        prefs.setStyle(calendarId, next)
        _state.update { it.copy(styles = it.styles + (calendarId to next)) }
    }

    fun setTextScale(scale: Float) {
        prefs.textScale = scale
        _state.update { it.copy(textScale = scale) }
    }

    // ---- connection ----

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered || connectivity == null) return
        networkCallbackRegistered = runCatching {
            connectivity.registerDefaultNetworkCallback(networkCallback)
        }.isSuccess
    }

    private fun hasInternet(): Boolean {
        val cm = connectivity ?: return true
        return runCatching {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    private fun markOffline() {
        _state.update { if (it.offlineSinceMillis == null) it.copy(offlineSinceMillis = System.currentTimeMillis()) else it }
    }

    private fun markOnline() {
        _state.update { if (it.offlineSinceMillis != null) it.copy(offlineSinceMillis = null) else it }
    }

    // ---- calendar ----

    private fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication<Application>(), Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun registerObserver() {
        if (observerRegistered || !hasCalendarPermission()) return
        observerRegistered = runCatching {
            getApplication<Application>().contentResolver
                .registerContentObserver(CalendarContract.CONTENT_URI, true, calendarObserver)
        }.isSuccess
    }

    private fun scheduleCalendarReload() {
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(1_500)
            reloadCalendar()
        }
    }

    private fun reloadCalendar() {
        calendarJob?.cancel()
        calendarJob = viewModelScope.launch {
            if (!hasCalendarPermission()) {
                _state.update { it.copy(hasCalendarPermission = false) }
                return@launch
            }
            try {
                val today = LocalDate.now()
                val mode = prefs.mode
                val result = withContext(Dispatchers.IO) {
                    val calendars = calendarRepo.calendars()
                    val shown = if (prefs.calendarsConfigured) {
                        val existing = calendars.map { it.id }.toSet()
                        prefs.selectedCalendarIds.intersect(existing)
                    } else {
                        calendars.filter { it.visible && it.syncing }.map { it.id }.toSet()
                    }
                    val (from, to) = mode.range(today)
                    Triple(calendars, shown, calendarRepo.events(from, to, shown))
                }
                val (calendars, shown, events) = result
                val styles = calendars.mapIndexed { i, cal ->
                    cal.id to (prefs.styleFor(cal.id) ?: (i % STYLE_COUNT))
                }.toMap()
                _state.update {
                    it.copy(
                        hasCalendarPermission = true,
                        calendars = calendars,
                        shownCalendarIds = shown,
                        styles = styles,
                        events = events,
                        calendarError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(calendarError = "Calendar read failed: ${e.message}") }
            }
        }
    }

    // ---- to-do ----

    private suspend fun resolveProjectId(client: TodoistClient): String? {
        val key = prefs.todoistToken + "|" + prefs.todoistProject
        if (key == cachedProjectKey && cachedProjectId != null) return cachedProjectId
        val id = client.findProjectId(prefs.todoistProject)
        cachedProjectKey = if (id != null) key else null
        cachedProjectId = id
        return id
    }

    private suspend fun refreshTodos(): Unit = todoMutex.withLock {
        val token = prefs.todoistToken
        if (token.isBlank()) {
            _state.update { it.copy(todoError = "Add the Todoist token in Settings.") }
            return@withLock
        }
        try {
            val client = TodoistClient(token)
            val projectId = resolveProjectId(client)
            if (projectId == null) {
                val label = prefs.todoistProject.ifBlank { "a shared project" }
                _state.update { it.copy(todoError = "No Todoist project called \"$label\" on this account.") }
                markOnline()
                return@withLock
            }
            val tasks = client.tasks(projectId)
            val now = System.currentTimeMillis()
            prefs.saveTasks(tasks, now)
            markOnline()
            _state.update {
                it.copy(
                    tasks = orderTasks(tasks.filter { t -> t.id !in completing }),
                    todoError = null,
                    lastTodoSync = millisToTime(now),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isNetworkProblem(e)) {
                markOffline()
            } else {
                markOnline() // Todoist answered, so the connection itself is fine
                _state.update { it.copy(todoError = "To-do sync failed: ${e.message}") }
            }
        }
    }

    /** Keeps Todoist's manual order and puts sub-tasks under their parent. */
    private fun orderTasks(tasks: List<TodoTask>): List<DisplayTask> {
        val ids = tasks.map { it.id }.toSet()
        val children = tasks.filter { it.parentId != null && it.parentId in ids }.groupBy { it.parentId }
        val roots = tasks.filter { it.parentId == null || it.parentId !in ids }.sortedBy { it.order }
        val out = mutableListOf<DisplayTask>()
        fun add(task: TodoTask, depth: Int) {
            out += DisplayTask(task, depth)
            children[task.id].orEmpty().sortedBy { it.order }.forEach { add(it, minOf(depth + 1, 3)) }
        }
        roots.forEach { add(it, 0) }
        return out
    }

    // ---- weather ----

    private suspend fun refreshWeather(): Unit = weatherMutex.withLock {
        val query = prefs.weatherPlace
        try {
            val place = prefs.cachedPlace(query)
                ?: WeatherClient.findPlace(query)?.also { prefs.savePlace(query, it) }
            if (place == null) {
                _state.update { it.copy(weatherError = "Couldn't find \"$query\" for the weather. Check it in Settings.") }
                markOnline()
                return@withLock
            }
            val weather = WeatherClient.forecast(place)
            prefs.saveWeather(weather)
            markOnline()
            _state.update { it.copy(weather = weather, weatherPlace = place.label, weatherError = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isNetworkProblem(e)) markOffline() else markOnline()
            if (_state.value.weather == null) _state.update { it.copy(weatherError = "Weather unavailable") }
        }
    }

    // ---- loops ----

    private suspend fun clockLoop() {
        while (viewModelScope.isActive) {
            val now = LocalDateTime.now()
            val dayChanged = now.toLocalDate() != _state.value.today
            _state.update { it.copy(today = now.toLocalDate(), now = now.toLocalTime().withSecond(0).withNano(0)) }
            if (dayChanged) {
                reloadCalendar()
                refreshTodos()
                refreshWeather()
                flash()
            }
            val msToNextMinute = 60_000 - (System.currentTimeMillis() % 60_000)
            delay(msToNextMinute + 100)
        }
    }

    private suspend fun todoLoop() {
        while (viewModelScope.isActive) {
            refreshTodos()
            delay(30_000L) // one small request each time
        }
    }

    private suspend fun weatherLoop() {
        while (viewModelScope.isActive) {
            refreshWeather()
            delay(30 * 60_000L)
        }
    }

    /** A black-then-white flash once an hour clears e-ink ghosting. */
    private suspend fun flashLoop() {
        while (viewModelScope.isActive) {
            val msToNextHour = 3_600_000 - (System.currentTimeMillis() % 3_600_000)
            delay(msToNextHour + 2_000)
            flash()
        }
    }

    private fun flash() = _state.update { it.copy(flashTick = it.flashTick + 1) }

    private fun millisToTime(ms: Long): LocalTime =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0)
}
