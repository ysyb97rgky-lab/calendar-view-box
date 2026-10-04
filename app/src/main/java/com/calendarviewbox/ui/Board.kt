package com.calendarviewbox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calendarviewbox.BoardState
import com.calendarviewbox.BoardViewModel
import com.calendarviewbox.DisplayTask
import com.calendarviewbox.ListKind
import com.calendarviewbox.OFFLINE_GRACE_MS
import com.calendarviewbox.data.CalendarMode
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun App(
    vm: BoardViewModel,
    onRequestPermission: () -> Unit,
    onOpenAndroidSettings: () -> Unit,
    onAddAccount: (googleOnly: Boolean) -> Unit,
    onManageAccounts: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val base = LocalDensity.current

    InkTheme {
        CompositionLocalProvider(
            LocalDensity provides Density(base.density, base.fontScale * state.textScale)
        ) {
            Box(Modifier.fillMaxSize().background(Ink.White)) {
                if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(
                        state = state,
                        vm = vm,
                        onClose = {
                            showSettings = false
                            vm.manualRefresh()
                        },
                        onOpenAndroidSettings = onOpenAndroidSettings,
                        onAddAccount = onAddAccount,
                        onManageAccounts = onManageAccounts,
                    )
                } else {
                    // Back does nothing on the board, so a stray tap can't close it.
                    BackHandler { }
                    Board(
                        state = state,
                        vm = vm,
                        onOpenSettings = { showSettings = true },
                        onRequestPermission = onRequestPermission,
                        onAdd = { adding = true },
                        onAddAccount = { onAddAccount(true) },
                    )
                    if (adding) {
                        BackHandler { adding = false }
                        AddTaskOverlay(
                            kind = state.activeList,
                            staples = if (state.activeList == ListKind.GROCERIES) state.staples else emptyList(),
                            onSubmit = { text, done -> vm.addTask(state.activeList, text, done) },
                            onClose = { adding = false },
                        )
                    }
                }
                FlashOverlay(state.flashTick)
            }
        }
    }
}

@Composable
private fun Board(
    state: BoardState,
    vm: BoardViewModel,
    onOpenSettings: () -> Unit,
    onRequestPermission: () -> Unit,
    onAdd: () -> Unit,
    onAddAccount: () -> Unit,
) {
    Row(Modifier.fillMaxSize().padding(24.dp)) {
        TodoPane(
            state = state,
            onComplete = vm::completeTask,
            onAdd = onAdd,
            onSwitch = vm::setActiveList,
            modifier = Modifier.weight(0.3f).fillMaxHeight(),
        )
        Spacer(Modifier.width(24.dp))
        Box(Modifier.width(3.dp).fillMaxHeight().background(Ink.Black))
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(0.7f).fillMaxHeight()) {
            Toolbar(
                mode = state.mode,
                onMode = vm::setMode,
                onRefresh = vm::manualRefresh,
                onSettings = onOpenSettings,
                updateLabel = state.update?.let { "Update" },
                onUpdate = vm::installUpdate,
            )
            state.updateStatus?.let {
                Text(it, fontSize = 18.sp, color = Ink.Black, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.hasCalendarPermission) {
                    CalendarArea(state)
                } else {
                    PermissionPrompt(onRequestPermission)
                }
            }
            Spacer(Modifier.height(12.dp))
            Legend(state, onAddAccount)
        }
    }
}

// ---------------- left: date and to-do ----------------

private val dayNameFmt = DateTimeFormatter.ofPattern("EEEE")
private val longDateFmt = DateTimeFormatter.ofPattern("d MMMM yyyy")
private val shortDateFmt = DateTimeFormatter.ofPattern("EEE d MMM")

@Composable
private fun TodoPane(
    state: BoardState,
    onComplete: (String) -> Unit,
    onAdd: () -> Unit,
    onSwitch: (ListKind) -> Unit,
    modifier: Modifier,
) {
    val active = state.activeList
    val list = state.list(active)
    Column(modifier) {
        // The one large element: a printed-calendar style weekday.
        Text(
            state.today.format(dayNameFmt),
            fontSize = 64.sp,
            lineHeight = 66.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            color = Ink.Black,
        )
        Text(state.today.format(longDateFmt), fontSize = 26.sp, fontFamily = FontFamily.Serif, color = Ink.Black)
        Text(formatClock(state.now, state.use24h), fontSize = 24.sp, color = Ink.DarkGrey)

        WeatherStrip(state)

        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(Ink.Black))
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            ListTab(ListKind.TODO, state.todo.tasks.size, active == ListKind.TODO, onSwitch)
            if (state.groceriesEnabled) {
                Spacer(Modifier.width(24.dp))
                ListTab(ListKind.GROCERIES, state.groceries.tasks.size, active == ListKind.GROCERIES, onSwitch)
            }
            Spacer(Modifier.weight(1f))
            InkButton("Add", onClick = onAdd)
        }
        Spacer(Modifier.height(8.dp))

        list.error?.let {
            Text(it, fontSize = 18.sp, color = Ink.Black, modifier = Modifier.padding(vertical = 6.dp))
        }
        if (list.tasks.isEmpty() && list.error == null) {
            Text(
                if (active == ListKind.TODO) "Nothing on the list. Add items in Todoist on your phone."
                else "Nothing to buy. Tap Add, or add items in Todoist.",
                fontSize = 20.sp,
                color = Ink.DarkGrey,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (active == ListKind.GROCERIES && list.sections.isNotEmpty()) {
                // Group by Todoist section (e.g. Fruit and veg, Dairy). Unsectioned items go first.
                val bySection = list.tasks.groupBy { row ->
                    row.task.sectionId?.takeIf { id -> list.sections.any { it.id == id } }
                }
                bySection[null]?.let { rows ->
                    items(rows, key = { it.task.id }) { row -> TaskRowFor(row, state, onComplete) }
                }
                list.sections.forEach { section ->
                    val rows = bySection[section.id].orEmpty()
                    if (rows.isNotEmpty()) {
                        item(key = "section_${section.id}") {
                            Text(
                                section.name,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Ink.DarkGrey,
                                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                            )
                        }
                        items(rows, key = { it.task.id }) { row -> TaskRowFor(row, state, onComplete) }
                    }
                }
            } else {
                items(list.tasks, key = { it.task.id }) { row -> TaskRowFor(row, state, onComplete) }
            }
        }

        // This pane already reads state.now for the clock, so the check reruns every minute.
        val offlineSince = state.offlineSinceMillis
            ?.takeIf { System.currentTimeMillis() - it >= OFFLINE_GRACE_MS }
        if (offlineSince != null) {
            OfflineNote(offlineSince, state.use24h)
        } else {
            list.lastSync?.let {
                Text("List updated ${formatClock(it, state.use24h)}", fontSize = 16.sp, color = Ink.Grey)
            }
        }
    }
}

@Composable
private fun TaskRowFor(row: DisplayTask, state: BoardState, onComplete: (String) -> Unit) {
    TaskRow(
        row = row,
        today = state.today,
        use24h = state.use24h,
        done = row.task.id in state.completingIds,
        onComplete = onComplete,
    )
}

/** "To do 8" / "Groceries 5". The open one is bold and underlined. */
@Composable
private fun ListTab(kind: ListKind, count: Int, selected: Boolean, onSwitch: (ListKind) -> Unit) {
    // IntrinsicSize keeps the underline as wide as the label instead of the whole row.
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .clickable(interactionSource = null, indication = null) { onSwitch(kind) }
            .padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                kind.title,
                fontSize = 30.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) Ink.Black else Ink.DarkGrey,
            )
            if (count > 0) {
                Spacer(Modifier.width(8.dp))
                Text("$count", fontSize = 22.sp, color = Ink.DarkGrey, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .height(3.dp)
                .fillMaxWidth()
                .background(if (selected) Ink.Black else Ink.White)
        )
    }
}

/** Shown when the board has had no connection for over a minute, so nobody trusts stale plans. */
@Composable
private fun OfflineNote(sinceMillis: Long, use24h: Boolean) {
    val since = Instant.ofEpochMilli(sinceMillis).atZone(ZoneId.systemDefault()).toLocalTime()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(Ink.Black, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text("Offline since ${formatClock(since, use24h)}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink.White)
        Text("The calendar and list may be out of date.", fontSize = 16.sp, color = Ink.White)
    }
}

/** Full-screen white panel with the keyboard, so nothing grey or animated sits over the board. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTaskOverlay(
    kind: ListKind,
    staples: List<String>,
    onSubmit: (String, (String?) -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    val submit: () -> Unit = {
        if (!busy) {
            busy = true
            message = "Adding..."
            onSubmit(text) { error ->
                busy = false
                if (error == null) {
                    keyboard?.hide()
                    onClose()
                } else {
                    message = error
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.White)
            .padding(horizontal = 48.dp, vertical = 40.dp)
    ) {
        Text(
            if (kind == ListKind.GROCERIES) "Add to groceries" else "Add to the list",
            fontSize = 40.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            color = Ink.Black,
        )
        Text(
            "It goes into the shared Todoist project, so it shows on both phones too.",
            fontSize = 18.sp,
            color = Ink.DarkGrey,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("e.g. Buy nappies") },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 24.sp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.width(900.dp).focusRequester(focus),
            )
            Spacer(Modifier.width(16.dp))
            InkButton("Add", selected = true, onClick = submit)
            Spacer(Modifier.width(10.dp))
            InkButton("Cancel") {
                keyboard?.hide()
                onClose()
            }
        }
        message?.let {
            Text(it, fontSize = 20.sp, color = Ink.Black, modifier = Modifier.padding(top = 16.dp))
        }
        if (staples.isNotEmpty()) {
            Text(
                "Quick add",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Ink.Black,
                modifier = Modifier.padding(top = 28.dp, bottom = 10.dp),
            )
            // One tap adds the item and leaves this panel open for more.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                staples.forEach { item ->
                    InkButton(item) {
                        message = "Adding $item..."
                        onSubmit(item) { error -> message = error ?: "Added $item." }
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    row: DisplayTask,
    today: LocalDate,
    use24h: Boolean,
    done: Boolean,
    onComplete: (String) -> Unit,
) {
    val task = row.task
    Row(
        Modifier.fillMaxWidth().padding(start = (row.depth * 28).dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Large tap target around a small circle.
        Box(
            Modifier
                .size(48.dp)
                .clickable(interactionSource = null, indication = null, enabled = !done) { onComplete(task.id) },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .background(if (done) Ink.Black else Ink.White, CircleShape)
                    .border(3.dp, Ink.Black, CircleShape)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(top = 8.dp)) {
            Text(
                task.content,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = if (done) Ink.Grey else Ink.Black,
                textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
            )
            dueLabel(task.due, task.dueTime, task.isRecurring, today, use24h)?.let { (label, overdue) ->
                Text(
                    label,
                    fontSize = 17.sp,
                    fontWeight = if (overdue) FontWeight.Bold else FontWeight.Normal,
                    color = if (overdue) Ink.Black else Ink.DarkGrey,
                )
            }
        }
    }
}

private fun dueLabel(
    due: LocalDate?,
    time: java.time.LocalTime?,
    recurring: Boolean,
    today: LocalDate,
    use24h: Boolean,
): Pair<String, Boolean>? {
    if (due == null) return null
    val overdue = due.isBefore(today)
    val day = when (due) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> due.format(shortDateFmt)
    }
    val parts = buildString {
        if (overdue) append("Overdue, ")
        append(day)
        if (time != null) append(" ").append(formatShortTime(time, use24h))
        if (recurring) append(", repeats")
    }
    return parts to overdue
}

// ---------------- right: toolbar, legend, prompts ----------------

@Composable
private fun Toolbar(
    mode: CalendarMode,
    onMode: (CalendarMode) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    updateLabel: String?,
    onUpdate: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CalendarMode.entries.forEach { m ->
                InkButton(m.label, selected = m == mode) { onMode(m) }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (updateLabel != null) InkButton(updateLabel, selected = true, onClick = onUpdate)
            InkButton("Refresh", onClick = onRefresh)
            InkButton("Settings", onClick = onSettings)
        }
    }
}

@Composable
private fun Legend(state: BoardState, onAddAccount: () -> Unit) {
    val shown = state.calendars.filter { it.id in state.shownCalendarIds }
    if (state.hasCalendarPermission && shown.isEmpty()) {
        // Nothing to show yet: offer sign-in right here instead of sending people to Android settings.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("No calendars yet.", fontSize = 18.sp, color = Ink.Black)
            Spacer(Modifier.width(16.dp))
            InkButton("Add Google account", selected = true, onClick = onAddAccount)
        }
        state.accountMessage?.let { Text(it, fontSize = 16.sp, color = Ink.Black, modifier = Modifier.padding(top = 6.dp)) }
        return
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEach { cal ->
            Marker(styleOf(state.styles[cal.id]), 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(cal.name, fontSize = 17.sp, color = Ink.DarkGrey, maxLines = 1)
            Spacer(Modifier.width(24.dp))
        }
        state.calendarError?.let { Text(it, fontSize = 16.sp, color = Ink.Black) }
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Allow calendar access to show your events here.", fontSize = 24.sp, color = Ink.Black)
        Spacer(Modifier.height(16.dp))
        InkButton("Allow calendar access", onClick = onRequest)
    }
}

/** Briefly fills the screen black then white, which forces a clean e-ink redraw. */
@Composable
private fun FlashOverlay(tick: Int) {
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        phase = 1
        delay(350)
        phase = 2
        delay(350)
        phase = 0
    }
    if (phase != 0) {
        Box(Modifier.fillMaxSize().background(if (phase == 1) Ink.Black else Ink.White))
    }
}
