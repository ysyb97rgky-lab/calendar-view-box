package com.calendarviewbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calendarviewbox.BoardState
import com.calendarviewbox.data.CalendarMode
import com.calendarviewbox.data.EventItem
import com.calendarviewbox.data.monthGrid
import com.calendarviewbox.data.range
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val weekdayFmt = DateTimeFormatter.ofPattern("EEE")
private val monthTitleFmt = DateTimeFormatter.ofPattern("MMMM yyyy")
private val dayMonthFmt = DateTimeFormatter.ofPattern("d MMM")
private val agendaDayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM")

@Composable
fun CalendarArea(state: BoardState, onOpenDay: (LocalDate) -> Unit) {
    when (state.mode) {
        CalendarMode.WEEK -> DayRows(state.today, 7, state, onOpenDay, Modifier.fillMaxSize())
        CalendarMode.TWO_WEEKS -> Row(Modifier.fillMaxSize()) {
            DayRows(state.today, 7, state, onOpenDay, Modifier.weight(1f).fillMaxHeight(), compact = true)
            Box(Modifier.padding(horizontal = 12.dp).width(1.dp).fillMaxHeight().background(Ink.Grey))
            DayRows(state.today.plusDays(7), 7, state, onOpenDay, Modifier.weight(1f).fillMaxHeight(), compact = true)
        }
        CalendarMode.MONTH -> {
            val (start, weeks) = monthGrid(state.today)
            Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
                Text(
                    state.today.format(monthTitleFmt),
                    fontSize = 30.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    color = Ink.Black,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                DayGrid(start = start, rows = weeks, state = state, month = YearMonth.from(state.today), onOpenDay = onOpenDay)
            }
        }
        CalendarMode.AGENDA -> Box(Modifier.fillMaxSize().padding(top = 12.dp)) { Agenda(state) }
    }
}

/** After 7pm the board looks ahead: tomorrow gets the bold treatment instead of today. */
fun isEvening(state: BoardState): Boolean = state.now.hour >= 19
fun focusDay(state: BoardState): LocalDate = if (isEvening(state)) state.today.plusDays(1) else state.today

private val dayLabelFmt = DateTimeFormatter.ofPattern("EEE")
private val dayLabelMonthFmt = DateTimeFormatter.ofPattern("EEE, MMM")

private fun dayLabel(date: LocalDate, state: BoardState): String = when (date) {
    state.today -> "Today"
    state.today.plusDays(1) -> "Tomorrow"
    else -> date.format(if (date.dayOfMonth == 1) dayLabelMonthFmt else dayLabelFmt)
}

/** One full-width row per day, so titles have room instead of squeezing into columns. */
@Composable
private fun DayRows(
    start: LocalDate,
    count: Int,
    state: BoardState,
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier,
    compact: Boolean = false,
) {
    val byDay = remember(state.events, start, count) { eventsByDay(state.events, start, count) }
    val focus = focusDay(state)
    Column(modifier) {
        for (i in 0 until count) {
            val date = start.plusDays(i.toLong())
            DayRow(
                date = date,
                events = byDay[date].orEmpty(),
                state = state,
                focused = date == focus,
                last = i == count - 1,
                compact = compact,
                onOpenDay = onOpenDay,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayRow(
    date: LocalDate,
    events: List<EventItem>,
    state: BoardState,
    focused: Boolean,
    last: Boolean,
    compact: Boolean,
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    val eventSize = if (compact) 20.sp else 24.sp
    val numberSize = if (compact) 30.sp else 38.sp
    val lineGap = 12.dp
    val next = remember(events, state.now, date) { nextEvent(events, date, state) }

    Row(
        modifier
            .clickable(interactionSource = null, indication = null) { onOpenDay(date) }
            .drawBehind {
                if (!last) {
                    drawLine(Ink.Grey, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                }
                if (focused) drawRect(Ink.Black, size = Size(6.dp.toPx(), size.height))
            }
            .padding(start = 22.dp)
    ) {
        Column(Modifier.width(if (compact) 86.dp else 104.dp).padding(top = 12.dp)) {
            Text(dayLabel(date, state), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Ink.DarkGrey, maxLines = 1)
            if (focused) {
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .background(Ink.Black, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp)
                ) {
                    Text("${date.dayOfMonth}", fontSize = numberSize, fontWeight = FontWeight.Bold, color = Ink.White)
                }
            } else {
                Text("${date.dayOfMonth}", fontSize = numberSize, fontWeight = FontWeight.Bold, color = Ink.Black)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(top = 16.dp, bottom = 8.dp)) {
            val density = LocalDensity.current
            val lineHeight = with(density) { (eventSize * 1.35f).toDp() }
            // As many lines as fit; the rest collapse into "+N more" (tap the row to see them).
            val lines = ((maxHeight + lineGap) / (lineHeight + lineGap)).toInt().coerceAtLeast(1)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(if (compact) 26.dp else 34.dp),
                verticalArrangement = Arrangement.spacedBy(lineGap),
                maxLines = lines,
                overflow = FlowRowOverflow.expandIndicator {
                    Text(
                        "+${totalItemCount - shownItemCount} more",
                        fontSize = eventSize,
                        fontWeight = FontWeight.Bold,
                        color = Ink.DarkGrey,
                    )
                },
            ) {
                events.forEach { e -> EventChip(e, date, state, eventSize, isNext = e == next) }
            }
        }
    }
}

/** The next timed event still to come today, or tomorrow's first one in the evening. */
private fun nextEvent(events: List<EventItem>, date: LocalDate, state: BoardState): EventItem? {
    val now = LocalDateTime.of(state.today, state.now)
    val isToday = date == state.today
    val isTomorrowEvening = isEvening(state) && date == state.today.plusDays(1)
    if (!isToday && !isTomorrowEvening) return null
    return events.firstOrNull { !it.allDay && it.startDate == date && it.start.isAfter(now) }
}

@Composable
private fun EventChip(e: EventItem, date: LocalDate, state: BoardState, size: TextUnit, isNext: Boolean) {
    val style = styleOf(state.styles[e.calendarId])
    if (e.allDay) {
        val shape = RoundedCornerShape(6.dp)
        var m = Modifier.background(style.fill, shape)
        if (style.border != null) m = m.border(2.dp, style.border, shape)
        val total = ChronoUnit.DAYS.between(e.startDate, e.endDateInclusive).toInt() + 1
        val index = ChronoUnit.DAYS.between(e.startDate, date).toInt() + 1
        Row(m.padding(horizontal = 14.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                e.title,
                fontSize = size * 0.88f,
                fontWeight = FontWeight.Medium,
                color = style.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (total > 1) {
                Spacer(Modifier.width(8.dp))
                Text("$index of $total", fontSize = size * 0.68f, color = style.text)
            }
        }
        return
    }

    val now = LocalDateTime.of(state.today, state.now)
    val past = !e.end.isAfter(now)
    val color = if (past) Ink.Grey else Ink.Black
    Row(verticalAlignment = Alignment.CenterVertically) {
        Marker(style, 15.dp)
        Spacer(Modifier.width(9.dp))
        val time = if (e.startDate == date) formatShortTime(e.start.toLocalTime(), state.use24h) else "cont."
        Text(time, fontSize = size, fontWeight = if (past) FontWeight.Medium else FontWeight.Bold, color = color)
        Spacer(Modifier.width(8.dp))
        Text(
            e.title,
            fontSize = size,
            fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        val location = e.location?.trim()
        if (!past && !location.isNullOrEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(
                location,
                fontSize = size * 0.8f,
                color = Ink.DarkGrey,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (isNext) {
            val mins = Duration.between(now, e.start).toMinutes()
            if (mins in 0..360) {
                Spacer(Modifier.width(10.dp))
                val label = if (mins < 60) "in $mins min" else "in ${(mins + 30) / 60} hr"
                Box(
                    Modifier
                        .border(2.dp, Ink.Black, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp)
                ) {
                    Text(label, fontSize = size * 0.7f, color = Ink.Black)
                }
            }
        }
    }
}

// ---------------- day detail ----------------

private val detailTitleFmt = DateTimeFormatter.ofPattern("EEEE d MMMM")

/** Everything on one day, with locations and notes. Returns to the board on its own after two minutes. */
@Composable
fun DayDetailOverlay(date: LocalDate, state: BoardState, onClose: () -> Unit) {
    val events = remember(state.events, date) { eventsByDay(state.events, date, 1)[date].orEmpty() }
    val due = state.todo.tasks.filter { it.task.due == date }
    LaunchedEffect(date) {
        delay(2 * 60_000L)
        onClose()
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.White)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 36.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val prefix = when (date) {
                state.today -> "Today, "
                state.today.plusDays(1) -> "Tomorrow, "
                else -> ""
            }
            Text(
                prefix + date.format(detailTitleFmt),
                fontSize = 44.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                color = Ink.Black,
            )
            Spacer(Modifier.weight(1f))
            InkButton("Close", selected = true, onClick = onClose)
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(Ink.Black))
        if (events.isEmpty()) {
            Text("Nothing on.", fontSize = 26.sp, color = Ink.DarkGrey, modifier = Modifier.padding(top = 24.dp))
        }
        events.forEach { e -> DetailRow(e, date, state) }
        if (due.isNotEmpty()) {
            Text(
                "Due on this day",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Ink.Black,
                modifier = Modifier.padding(top = 32.dp, bottom = 8.dp),
            )
            due.forEach { row ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).border(3.dp, Ink.Black, RoundedCornerShape(50)))
                    Spacer(Modifier.width(14.dp))
                    Text(row.task.content, fontSize = 24.sp, color = Ink.Black)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(e: EventItem, date: LocalDate, state: BoardState) {
    val style = styleOf(state.styles[e.calendarId])
    val time = when {
        e.allDay -> "All day"
        e.startDate != date -> "Continues"
        else -> formatShortTime(e.start.toLocalTime(), state.use24h) + "–" + formatShortTime(e.end.toLocalTime(), state.use24h)
    }
    val calendarName = state.calendars.firstOrNull { it.id == e.calendarId }?.name
    val notes = e.description?.let {
        android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()
    }?.takeIf { it.isNotEmpty() }

    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(Ink.LightGrey, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 8.dp)) { Marker(style, 22.dp) }
        Spacer(Modifier.width(18.dp))
        Text(time, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink.Black, modifier = Modifier.width(260.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, fontSize = 28.sp, color = Ink.Black)
            e.location?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 20.sp, color = Ink.DarkGrey) }
            notes?.let {
                Text(it, fontSize = 20.sp, color = Ink.DarkGrey, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
            calendarName?.let { Text(it, fontSize = 16.sp, color = Ink.Grey, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

/** Spreads each event across every day it touches, sorted all-day first then by time. */
fun eventsByDay(events: List<EventItem>, start: LocalDate, days: Int): Map<LocalDate, List<EventItem>> {
    val endExclusive = start.plusDays(days.toLong())
    val map = HashMap<LocalDate, MutableList<EventItem>>()
    for (e in events) {
        // LocalDate is Comparable<ChronoLocalDate>, so pick with isAfter instead of maxOf/minOf.
        var d = if (e.startDate.isAfter(start)) e.startDate else start
        val cap = endExclusive.minusDays(1)
        val last = if (e.endDateInclusive.isBefore(cap)) e.endDateInclusive else cap
        while (!d.isAfter(last)) {
            map.getOrPut(d) { mutableListOf() }.add(e)
            d = d.plusDays(1)
        }
    }
    map.values.forEach { list ->
        list.sortWith(compareBy<EventItem>({ !it.allDay }, { it.start }, { it.title }))
    }
    return map
}

// ---------------- grid views ----------------

@Composable
private fun DayGrid(start: LocalDate, rows: Int, state: BoardState, month: YearMonth?, onOpenDay: (LocalDate) -> Unit) {
    val byDay = remember(state.events, start, rows) { eventsByDay(state.events, start, rows * 7) }
    val compact = rows > 2
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                Text(
                    start.plusDays(i.toLong()).format(weekdayFmt),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink.DarkGrey,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.Black))
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (c in 0 until 7) {
                    val date = start.plusDays((r * 7 + c).toLong())
                    DayCell(
                        date = date,
                        events = byDay[date].orEmpty(),
                        state = state,
                        outsideMonth = month != null && YearMonth.from(date) != month,
                        compact = compact,
                        wrapLines = if (rows == 1) 3 else 1,
                        drawRightEdge = c < 6,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(interactionSource = null, indication = null) { onOpenDay(date) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    events: List<EventItem>,
    state: BoardState,
    outsideMonth: Boolean,
    compact: Boolean,
    wrapLines: Int,
    drawRightEdge: Boolean,
    modifier: Modifier,
) {
    val eventSize = if (compact) 15.sp else 18.sp
    val badgeSize = if (compact) 20.sp else 24.sp
    BoxWithConstraints(
        modifier
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(Ink.Grey, Offset(0f, size.height), Offset(size.width, size.height), stroke)
                if (drawRightEdge) {
                    drawLine(Ink.Grey, Offset(size.width, 0f), Offset(size.width, size.height), stroke)
                }
            }
            .padding(6.dp)
    ) {
        val density = LocalDensity.current
        val lineHeight = with(density) { (eventSize * 1.7f).toDp() }
        val headerHeight = with(density) { (badgeSize * 1.8f).toDp() }
        // In the week view titles wrap, so budget about two lines per event.
        val perEvent = if (wrapLines > 1) lineHeight * 2 else lineHeight
        val capacity = ((maxHeight - headerHeight) / perEvent).toInt().coerceAtLeast(1)

        Column {
            DateBadge(date, isToday = date == state.today, outsideMonth = outsideMonth, size = badgeSize)
            val visible = if (events.size > capacity) events.take(capacity - 1) else events
            visible.forEach { EventLine(it, date, state, eventSize, wrapLines) }
            if (events.size > visible.size) {
                Text("+${events.size - visible.size} more", fontSize = eventSize, color = Ink.DarkGrey)
            }
        }
    }
}

@Composable
private fun DateBadge(date: LocalDate, isToday: Boolean, outsideMonth: Boolean, size: TextUnit) {
    val label = if (date.dayOfMonth == 1) date.format(dayMonthFmt) else date.dayOfMonth.toString()
    if (isToday) {
        Box(
            Modifier
                .padding(bottom = 4.dp)
                .background(Ink.Black, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 2.dp)
        ) {
            Text(label, fontSize = size, fontWeight = FontWeight.Bold, color = Ink.White)
        }
    } else {
        Text(
            label,
            fontSize = size,
            fontWeight = FontWeight.Bold,
            color = if (outsideMonth) Ink.Grey else Ink.Black,
            modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 6.dp),
        )
    }
}

@Composable
private fun EventLine(e: EventItem, date: LocalDate, state: BoardState, size: TextUnit, maxLines: Int) {
    val style = styleOf(state.styles[e.calendarId])
    if (e.allDay) {
        val shape = RoundedCornerShape(4.dp)
        var m = Modifier.fillMaxWidth().padding(vertical = 2.dp).background(style.fill, shape)
        if (style.border != null) m = m.border(2.dp, style.border, shape)
        Box(m.padding(horizontal = 6.dp, vertical = 1.dp)) {
            Text(
                e.title,
                fontSize = size,
                fontWeight = FontWeight.SemiBold,
                color = style.text,
                maxLines = minOf(maxLines, 2),
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 5.dp)) { Marker(style, 12.dp) }
            Spacer(Modifier.width(6.dp))
            val label = if (e.startDate == date) {
                formatShortTime(e.start.toLocalTime(), state.use24h) + " " + e.title
            } else {
                "cont. " + e.title
            }
            Text(label, fontSize = size, color = Ink.Black, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------- agenda ----------------

@Composable
private fun Agenda(state: BoardState) {
    val (from, to) = CalendarMode.AGENDA.range(state.today)
    val dayCount = ChronoUnit.DAYS.between(from, to).toInt()
    val byDay = remember(state.events, from) { eventsByDay(state.events, from, dayCount) }
    val days = (0 until dayCount).map { from.plusDays(it.toLong()) }
        .filter { it == state.today || byDay[it].orEmpty().isNotEmpty() }

    LazyColumn(Modifier.fillMaxSize()) {
        days.forEach { day ->
            item {
                val title = when (day) {
                    state.today -> "Today, " + day.format(agendaDayFmt)
                    state.today.plusDays(1) -> "Tomorrow, " + day.format(agendaDayFmt)
                    else -> day.format(agendaDayFmt)
                }
                Column(Modifier.padding(top = 18.dp, bottom = 6.dp)) {
                    Text(
                        title,
                        fontSize = 26.sp,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        color = Ink.Black,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.Black))
                }
            }
            val list = byDay[day].orEmpty()
            if (list.isEmpty()) {
                item { Text("Nothing on", fontSize = 22.sp, color = Ink.DarkGrey, modifier = Modifier.padding(vertical = 8.dp)) }
            }
            items(list) { e -> AgendaRow(e, day, state) }
        }
    }
}

@Composable
private fun AgendaRow(e: EventItem, day: LocalDate, state: BoardState) {
    val style = styleOf(state.styles[e.calendarId])
    val time = when {
        e.allDay -> "All day"
        e.startDate != day -> "Continues"
        else -> formatShortTime(e.start.toLocalTime(), state.use24h) + "–" +
            formatShortTime(e.end.toLocalTime(), state.use24h)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp)) { Marker(style, 18.dp) }
        Spacer(Modifier.width(14.dp))
        Text(time, fontSize = 22.sp, color = Ink.DarkGrey, modifier = Modifier.width(220.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, fontSize = 24.sp, color = Ink.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            e.location?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 18.sp, color = Ink.DarkGrey, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
