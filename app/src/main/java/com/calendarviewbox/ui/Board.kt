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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
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
import com.calendarviewbox.StoreSearchState
import com.calendarviewbox.data.CalendarMode
import com.calendarviewbox.data.StoreProduct
import com.calendarviewbox.data.TodoTask
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
    var openDay by remember { mutableStateOf<LocalDate?>(null) }
    var pricing by remember { mutableStateOf<TodoTask?>(null) }
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
                        onEditDinner = { day ->
                            showSettings = false
                            openDay = day
                        },
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
                        onOpenDay = { openDay = it },
                        onEditPrice = { pricing = it },
                    )
                    pricing?.let { task ->
                        val close = { pricing = null; vm.clearStoreSearch(); vm.panelClosed() }
                        BackHandler { close() }
                        PriceEditorOverlay(task, state, vm, onClose = close)
                    }
                    openDay?.let { day ->
                        BackHandler { openDay = null; vm.panelClosed() }
                        DayDetailOverlay(day, state, vm, onClose = { openDay = null; vm.panelClosed() })
                    }
                    if (adding) {
                        BackHandler { adding = false; vm.panelClosed() }
                        AddTaskOverlay(
                            kind = state.activeList,
                            staples = if (state.activeList == ListKind.GROCERIES) state.staples else emptyList(),
                            search = state.storeSearch,
                            onSubmit = { text, done -> vm.addTask(state.activeList, text, done) },
                            onSearch = vm::searchStores,
                            onPick = { product, typed, done -> vm.addGroceryProduct(product, typed, done) },
                            onClose = { adding = false; vm.clearStoreSearch(); vm.panelClosed() },
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
    onOpenDay: (LocalDate) -> Unit,
    onEditPrice: (TodoTask) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 18.dp)) {
        HeaderStrip(state, onMode = vm::setMode)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(3.dp).background(Ink.Black))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            ListsColumn(
                state = state,
                onComplete = vm::completeTask,
                onAdd = onAdd,
                onSwitch = vm::setActiveList,
                onEditPrice = onEditPrice,
                modifier = Modifier.width(400.dp).fillMaxHeight().padding(top = 18.dp, end = 26.dp),
            )
            Box(Modifier.width(2.dp).fillMaxHeight().background(Ink.Black))
            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp)) {
                when {
                    !state.hasCalendarPermission -> PermissionPrompt(onRequestPermission)
                    state.calendars.isEmpty() || state.shownCalendarIds.isEmpty() -> NoCalendarsPrompt(state, onAddAccount)
                    else -> CalendarArea(state, onOpenDay, onTickChore = { vm.toggleChore(it) })
                }
            }
        }
        Footer(state, onRefresh = vm::manualRefresh, onSettings = onOpenSettings, onUpdate = vm::installUpdate)
    }
}

// ---------------- header strip ----------------

private val dayNameFmt = DateTimeFormatter.ofPattern("EEEE")
private val longDateFmt = DateTimeFormatter.ofPattern("d MMMM yyyy")
private val shortDateFmt = DateTimeFormatter.ofPattern("EEE d MMM")

/** Date and time, weather, and the view switcher in one strip, so the calendar gets the full height. */
@Composable
internal fun HeaderStrip(state: BoardState, onMode: (CalendarMode) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(
                state.today.format(dayNameFmt),
                fontSize = 58.sp,
                lineHeight = 60.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                color = Ink.Black,
            )
            Text(
                state.today.format(longDateFmt) + ", " + formatClock(state.now, state.use24h),
                fontSize = 22.sp,
                color = Ink.DarkGrey,
            )
        }
        Box(
            Modifier
                .padding(horizontal = 32.dp, vertical = 6.dp)
                .width(2.dp)
                .fillMaxHeight()
                .background(Ink.Black)
        )
        WeatherHeader(state)
        Spacer(Modifier.weight(1f))
        ModeSwitch(state.mode, onMode)
    }
}

@Composable
private fun ModeSwitch(mode: CalendarMode, onMode: (CalendarMode) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .height(IntrinsicSize.Min)
            .border(2.dp, Ink.Black, shape)
            .clip(shape)
    ) {
        CalendarMode.entries.forEachIndexed { i, m ->
            if (i > 0) Box(Modifier.width(2.dp).fillMaxHeight().background(Ink.Black))
            val selected = m == mode
            Box(
                Modifier
                    .background(if (selected) Ink.Black else Ink.White)
                    .clickable(interactionSource = null, indication = null) { onMode(m) }
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    m.label,
                    fontSize = 20.sp,
                    color = if (selected) Ink.White else Ink.Black,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

// ---------------- lists column ----------------

@Composable
internal fun ListsColumn(
    state: BoardState,
    onComplete: (String) -> Unit,
    onAdd: () -> Unit,
    onSwitch: (ListKind) -> Unit,
    modifier: Modifier,
    onEditPrice: (TodoTask) -> Unit = {},
) {
    val active = state.activeList
    val list = state.list(active)
    val priceTag: (@Composable (TodoTask) -> Unit)? =
        if (active == ListKind.GROCERIES) { task -> PriceTag(task, state, onEditPrice) } else null
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ListTab(ListKind.TODO, state.todo.tasks.size, active == ListKind.TODO, onSwitch)
            if (state.groceriesEnabled) {
                Spacer(Modifier.width(22.dp))
                ListTab(ListKind.GROCERIES, state.groceries.tasks.size, active == ListKind.GROCERIES, onSwitch)
            }
            Spacer(Modifier.weight(1f))
            InkButton("Add", onClick = onAdd)
        }
        Spacer(Modifier.height(10.dp))

        list.error?.let {
            Text(it, fontSize = 18.sp, color = Ink.Black, modifier = Modifier.padding(vertical = 6.dp))
        }
        if (active == ListKind.GROCERIES) GroceryTotal(list.tasks, state)
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
                    items(rows, key = { it.task.id }) { row -> TaskRowFor(row, state, onComplete, priceTag) }
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
                        items(rows, key = { it.task.id }) { row -> TaskRowFor(row, state, onComplete, priceTag) }
                    }
                }
            } else {
                items(list.tasks, key = { it.task.id }) { row -> TaskRowFor(row, state, onComplete, priceTag) }
            }
        }
    }
}

@Composable
private fun TaskRowFor(
    row: DisplayTask,
    state: BoardState,
    onComplete: (String) -> Unit,
    trailing: (@Composable (TodoTask) -> Unit)? = null,
) {
    TaskRow(
        row = row,
        today = state.today,
        use24h = state.use24h,
        evening = isEvening(state),
        done = row.task.id in state.completingIds,
        onComplete = onComplete,
        trailing = trailing,
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
                fontSize = 28.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) Ink.Black else Ink.DarkGrey,
            )
            if (count > 0) {
                Spacer(Modifier.width(6.dp))
                Text("$count", fontSize = 20.sp, color = Ink.DarkGrey, modifier = Modifier.padding(bottom = 2.dp))
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

@Composable
private fun TaskRow(
    row: DisplayTask,
    today: LocalDate,
    use24h: Boolean,
    evening: Boolean,
    done: Boolean,
    onComplete: (String) -> Unit,
    trailing: (@Composable (TodoTask) -> Unit)? = null,
) {
    val task = row.task
    Row(
        Modifier.fillMaxWidth().padding(start = (row.depth * 28).dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Large tap target around a small circle.
        Box(
            Modifier
                .size(46.dp)
                .clickable(interactionSource = null, indication = null, enabled = !done) { onComplete(task.id) },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(if (done) Ink.Black else Ink.White, CircleShape)
                    .border(3.dp, Ink.Black, CircleShape)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(top = 8.dp)) {
            Text(
                task.content,
                fontSize = 23.sp,
                lineHeight = 29.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = if (done) Ink.Grey else Ink.Black,
                textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
            )
            dueLabel(task.due, task.dueTime, task.isRecurring, today, use24h, evening)?.let { (label, strong) ->
                Text(
                    label,
                    fontSize = 16.sp,
                    fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
                    color = if (strong) Ink.Black else Ink.DarkGrey,
                )
            }
        }
        trailing?.invoke(task)
    }
}

/** Due label and whether to make it stand out: overdue, or due tomorrow once it's evening. */
private fun dueLabel(
    due: LocalDate?,
    time: java.time.LocalTime?,
    recurring: Boolean,
    today: LocalDate,
    use24h: Boolean,
    evening: Boolean,
): Pair<String, Boolean>? {
    if (due == null) return null
    val overdue = due.isBefore(today)
    val tomorrow = due == today.plusDays(1)
    val day = when {
        due == today -> "Today"
        tomorrow && evening -> "Due tomorrow"
        tomorrow -> "Tomorrow"
        else -> due.format(shortDateFmt)
    }
    val text = buildString {
        if (overdue) append("Overdue, ")
        append(day)
        if (time != null) append(" ").append(formatShortTime(time, use24h))
        if (recurring) append(", repeats")
    }
    return text to (overdue || (tomorrow && evening))
}

// ---------------- footer ----------------

/** Legend on the left; status, Update, Refresh and Settings on the right. */
@Composable
internal fun Footer(state: BoardState, onRefresh: () -> Unit, onSettings: () -> Unit, onUpdate: () -> Unit) {
    // Reads state.now (via the clock in the header), so the offline check reruns every minute.
    val offlineSince = state.offlineSinceMillis?.takeIf { System.currentTimeMillis() - it >= OFFLINE_GRACE_MS }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.Black))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (offlineSince != null) {
                OfflinePill(offlineSince, state.use24h)
                Spacer(Modifier.width(24.dp))
            }
            Legend(state, Modifier.weight(1f))
            state.updateStatus?.let {
                Text(
                    it,
                    fontSize = 17.sp,
                    color = Ink.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 560.dp).padding(end = 16.dp),
                )
            }
            if (offlineSince == null) {
                state.todo.lastSync?.let {
                    Text(
                        "Updated ${formatClock(it, state.use24h)}",
                        fontSize = 16.sp,
                        color = Ink.Grey,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                }
            }
            if (state.update != null) {
                InkButton("Update", selected = true, small = true, onClick = onUpdate)
                Spacer(Modifier.width(10.dp))
            }
            InkButton("Refresh", small = true, onClick = onRefresh)
            Spacer(Modifier.width(10.dp))
            InkButton("Settings", small = true, onClick = onSettings)
        }
    }
}

@Composable
private fun Legend(state: BoardState, modifier: Modifier) {
    val shown = state.calendars.filter { it.id in state.shownCalendarIds }
    Row(modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        shown.forEach { cal ->
            Marker(styleOf(state.styles[cal.id]), 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(cal.name, fontSize = 17.sp, color = Ink.DarkGrey, maxLines = 1)
            Spacer(Modifier.width(26.dp))
        }
        state.calendarError?.let { Text(it, fontSize = 16.sp, color = Ink.Black) }
    }
}

/** Shown when the board has had no connection for over a minute, so nobody trusts stale plans. */
@Composable
private fun OfflinePill(sinceMillis: Long, use24h: Boolean) {
    val since = Instant.ofEpochMilli(sinceMillis).atZone(ZoneId.systemDefault()).toLocalTime()
    Box(
        Modifier
            .background(Ink.Black, RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            "Offline since ${formatClock(since, use24h)}. The calendar and lists may be out of date.",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Ink.White,
        )
    }
}

// ---------------- prompts ----------------

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

@Composable
private fun NoCalendarsPrompt(state: BoardState, onAddAccount: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (state.calendars.isEmpty()) "No calendars yet." else "No calendars are ticked. Choose them in Settings.",
            fontSize = 26.sp,
            color = Ink.Black,
        )
        if (state.calendars.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            InkButton("Add Google account", selected = true, onClick = onAddAccount)
        }
        state.accountMessage?.let {
            Text(it, fontSize = 18.sp, color = Ink.Black, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

/** Full-screen white panel with the keyboard, so nothing grey or animated sits over the board. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTaskOverlay(
    kind: ListKind,
    staples: List<String>,
    search: StoreSearchState,
    onSubmit: (String, (String?) -> Unit) -> Unit,
    onSearch: (String) -> Unit,
    onPick: (StoreProduct, String, (String?) -> Unit) -> Unit,
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
            .verticalScroll(rememberScrollState())
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
                modifier = Modifier.width(if (kind == ListKind.GROCERIES) 700.dp else 900.dp).focusRequester(focus),
            )
            Spacer(Modifier.width(16.dp))
            InkButton("Add", selected = true, onClick = submit)
            if (kind == ListKind.GROCERIES) {
                Spacer(Modifier.width(10.dp))
                InkButton("Find prices") {
                    if (text.isBlank()) message = "Type what you're after first, e.g. milk."
                    else {
                        keyboard?.hide()
                        message = null
                        onSearch(text)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            InkButton("Cancel") {
                keyboard?.hide()
                onClose()
            }
        }
        message?.let {
            Text(it, fontSize = 20.sp, color = Ink.Black, modifier = Modifier.padding(top = 16.dp))
        }
        if (kind == ListKind.GROCERIES) {
            // Tap a result to add that exact product, with its price remembered for next time.
            StoreResultsList(search) { product ->
                message = "Adding ${product.name}..."
                onPick(product, text) { error ->
                    message = error ?: "Added ${product.name}, ${com.calendarviewbox.data.Prices.money(product.price)}."
                }
            }
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
