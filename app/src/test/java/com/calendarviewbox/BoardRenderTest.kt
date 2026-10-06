package com.calendarviewbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.calendarviewbox.data.COOK_TURNS
import com.calendarviewbox.data.CalendarInfo
import com.calendarviewbox.data.CalendarMode
import com.calendarviewbox.data.Chore
import com.calendarviewbox.data.DayWeather
import com.calendarviewbox.data.DinnerPlan
import com.calendarviewbox.data.EventItem
import com.calendarviewbox.data.Household
import com.calendarviewbox.data.STORE_COLES
import com.calendarviewbox.data.STORE_WOOLWORTHS
import com.calendarviewbox.data.StoreProduct
import com.calendarviewbox.data.StoreResults
import com.calendarviewbox.data.TodoTask
import com.calendarviewbox.data.Weather
import com.calendarviewbox.ui.CalendarArea
import com.calendarviewbox.ui.DayDetailOverlay
import com.calendarviewbox.ui.Footer
import com.calendarviewbox.ui.HeaderStrip
import com.calendarviewbox.ui.Ink
import com.calendarviewbox.ui.InkTheme
import com.calendarviewbox.ui.ListsColumn
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Draws the whole board with realistic data in every view. Any crash fails the build. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1600dp-h1200dp-land-xhdpi")
class BoardRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.now()

    private fun timed(cal: Long, title: String, dayOffset: Long, hour: Int, minutes: Int, loc: String? = null): EventItem {
        val start = today.plusDays(dayOffset).atTime(hour, 0)
        val end = start.plusMinutes(minutes.toLong())
        return EventItem(1, cal, title, loc, null, false, start, end, start.toLocalDate(), end.toLocalDate())
    }

    private fun allDay(cal: Long, title: String, dayOffset: Long, days: Long): EventItem {
        val s = today.plusDays(dayOffset)
        return EventItem(2, cal, title, null, "Notes <b>with</b> html", true, s.atStartOfDay(), s.plusDays(days - 1).atStartOfDay(), s, s.plusDays(days - 1))
    }

    internal fun sampleState(): BoardState {
        val events = listOf(
            allDay(4, "Labour Day", 0, 1),
            timed(1, "Gym", 0, 6, 60),
            timed(2, "Brunch with Sam", 0, 11, 90, "Cafe"),
            timed(3, "Swimming lessons", 0, 23, 45, "Aquatic centre"),
            timed(1, "Car service", 1, 8, 60, "Garage"),
            timed(1, "Electrician quote for the outdoor lights, a very long title to force wrapping", 3, 16, 60),
            allDay(3, "Grandparents visiting", 4, 3),
            timed(3, "Overnight", 5, 22, 240),
        ) + (0 until 14).map { timed(2, "Busy item $it", 2, 9, 30) } // a very busy day
        val people = Household.defaultPeople
        val chores = listOf(
            Chore("c1", "Dishes", emptySet(), people.map { it.id }, 0, today.minusDays(3)),
            Chore("c2", "Bins", setOf(DayOfWeek.WEDNESDAY), people.map { it.id }, 1, today.minusDays(10)),
            Chore("c3", "Vacuum", setOf(DayOfWeek.SATURDAY), people.map { it.id }, 0, today.minusDays(10), lastDone = today, lastDoneBy = "p_you", lastDoneAt = 0L),
        )
        val dinners = listOf(
            DinnerPlan("d1", "Tacos", "p_you", 2, today),
            DinnerPlan("d2", "Pizza", COOK_TURNS, 1, today.plusDays(1), people.map { it.id }),
            DinnerPlan("d3", "Leftovers", "anyone", 0, today.plusDays(2)),
        )
        val tasks = listOf(
            DisplayTask(TodoTask("t1", "Pay electricity bill", null, 0, today.minusDays(2), null, false), 0),
            DisplayTask(TodoTask("t2", "Book car service", null, 1, today.plusDays(1), LocalTime.of(9, 0), true), 0),
            DisplayTask(TodoTask("t3", "Wrap present", "t2", 0, null, null, false), 1),
        )
        val weather = Weather(20, 2, (0 until 6).map { DayWeather(today.plusDays(it.toLong()), listOf(0, 2, 3, 61, 95, 45)[it], 24, 15, 40) }, System.currentTimeMillis())
        return BoardState(
            now = LocalTime.of(20, 15), // evening, so tomorrow takes focus
            hasCalendarPermission = true,
            calendars = (1L..4L).map { CalendarInfo(it, "Cal $it", "a@b.c", "com.google", true, true) },
            shownCalendarIds = setOf(1L, 2L, 3L, 4L),
            styles = mapOf(1L to 0, 2L to 1, 3L to 2, 4L to 3),
            events = events,
            todo = TaskList(tasks = tasks),
            groceries = TaskList(tasks = tasks.take(1)),
            weather = weather,
            offlineSinceMillis = System.currentTimeMillis() - 5 * 60_000L,
            update = null,
            household = people,
            chores = chores,
            dinners = dinners,
            recentMeals = listOf("Tacos", "Pizza"),
            taskPrices = mapOf("t1" to StoreProduct(STORE_WOOLWORTHS, "1", "Norco Full Cream Milk", "3L", 4.5, 5.0, "$1.50 / 1L", true)),
            namePrices = mapOf("book car service" to StoreProduct("Other", null, "Book car service", null, 120.0)),
            storeSearch = StoreSearchState(
                query = "milk",
                results = listOf(
                    StoreResults(STORE_WOOLWORTHS, listOf(StoreProduct(STORE_WOOLWORTHS, "1", "Norco Full Cream Milk", "3L", 4.5))),
                    StoreResults(STORE_COLES, emptyList(), "Coles search isn't working right now.", "No results after 35s. Pages loaded: 1.", "https://www.coles.com.au/search/products?q=milk"),
                ),
            ),
        )
    }

    /** Same layout as the real board, minus the ViewModel. */
    @Composable
    internal fun TestBoard(state: BoardState) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            HeaderStrip(state, onMode = {})
            Row(Modifier.weight(1f).fillMaxWidth()) {
                ListsColumn(state, onComplete = {}, onAdd = {}, onSwitch = {}, modifier = Modifier.width(com.calendarviewbox.ui.listsWidth()).fillMaxHeight())
                Box(Modifier.width(2.dp).fillMaxHeight().background(Ink.Black))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    CalendarArea(state, onOpenDay = {}, onTickChore = {})
                }
            }
            Footer(state, onMode = {}, onRefresh = {}, onSettings = {}, onUpdate = {})
        }
    }

    @Test
    fun boardDrawsInEveryView() {
        var state by mutableStateOf(sampleState())
        compose.setContent { InkTheme { TestBoard(state) } }
        compose.waitForIdle()
        for (mode in CalendarMode.entries) {
            state = state.copy(mode = mode)
            compose.waitForIdle()
        }
        state = state.copy(mode = CalendarMode.WEEK, now = LocalTime.of(9, 0), offlineSinceMillis = null)
        compose.waitForIdle()
        state = state.copy(activeList = ListKind.GROCERIES)
        compose.waitForIdle()
        state = state.copy(chores = emptyList(), dinners = emptyList())
        compose.waitForIdle()
        state = state.copy(events = emptyList(), weather = null)
        compose.waitForIdle()
    }

    @Test
    fun viewModelStartsAndAppDraws() {
        val vm = BoardViewModel(ApplicationProvider.getApplicationContext())
        compose.setContent {
            com.calendarviewbox.ui.App(vm, onRequestPermission = {}, onOpenAndroidSettings = {}, onAddAccount = {}, onManageAccounts = {})
        }
        compose.waitForIdle()
    }

    @Test
    fun settingsDraw() {
        val vm = BoardViewModel(ApplicationProvider.getApplicationContext())
        val state = sampleState()
        compose.setContent {
            InkTheme {
                com.calendarviewbox.ui.SettingsScreen(
                    state = state, vm = vm, onClose = {}, onOpenAndroidSettings = {},
                    onAddAccount = {}, onManageAccounts = {}, onEditDinner = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun priceEditorDraws() {
        val vm = BoardViewModel(ApplicationProvider.getApplicationContext())
        val state = sampleState()
        compose.setContent {
            InkTheme { com.calendarviewbox.ui.PriceEditorOverlay(state.groceries.tasks.first().task, state, vm, onClose = {}) }
        }
        compose.waitForIdle()
    }

    @Test
    fun quantitiesAndKeys() {
        val q = com.calendarviewbox.data.Prices.quantity("2x Milk")
        org.junit.Assert.assertEquals(2, q.count)
        org.junit.Assert.assertEquals("Milk", q.name)
        org.junit.Assert.assertEquals(3, com.calendarviewbox.data.Prices.quantity("Bananas x3").count)
        org.junit.Assert.assertEquals("milk", com.calendarviewbox.data.Prices.key("2 x  Milk "))
        org.junit.Assert.assertEquals(4.95, com.calendarviewbox.data.Prices.parseMoney("$4.95")!!, 0.001)
    }

    @Test
    fun dayPanelDraws() {
        val vm = BoardViewModel(ApplicationProvider.getApplicationContext())
        val state = sampleState()
        compose.setContent { InkTheme { DayDetailOverlay(today, state, vm, onClose = {}) } }
        compose.waitForIdle()
    }
}
