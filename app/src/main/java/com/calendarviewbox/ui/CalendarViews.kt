package com.calendarviewbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val weekdayFmt = DateTimeFormatter.ofPattern("EEE")
private val monthTitleFmt = DateTimeFormatter.ofPattern("MMMM yyyy")
private val dayMonthFmt = DateTimeFormatter.ofPattern("d MMM")
private val agendaDayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM")

@Composable
fun CalendarArea(state: BoardState) {
    when (state.mode) {
        CalendarMode.WEEK -> {
            val (from, _) = state.mode.range(state.today)
            DayGrid(start = from, rows = 1, state = state, month = null)
        }
        CalendarMode.TWO_WEEKS -> {
            val (from, _) = state.mode.range(state.today)
            DayGrid(start = from, rows = 2, state = state, month = null)
        }
        CalendarMode.MONTH -> {
            val (start, weeks) = monthGrid(state.today)
            Column(Modifier.fillMaxSize()) {
                Text(
                    state.today.format(monthTitleFmt),
                    fontSize = 30.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    color = Ink.Black,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                DayGrid(start = start, rows = weeks, state = state, month = YearMonth.from(state.today))
            }
        }
        CalendarMode.AGENDA -> Agenda(state)
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
private fun DayGrid(start: LocalDate, rows: Int, state: BoardState, month: YearMonth?) {
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
                        modifier = Modifier.weight(1f).fillMaxHeight(),
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
