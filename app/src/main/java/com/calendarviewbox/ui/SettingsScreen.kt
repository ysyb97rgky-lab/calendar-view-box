package com.calendarviewbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calendarviewbox.BoardState
import com.calendarviewbox.BoardViewModel
import com.calendarviewbox.data.COOK_ANYONE
import com.calendarviewbox.data.COOK_TURNS
import com.calendarviewbox.data.Chore
import com.calendarviewbox.data.DinnerPlan
import com.calendarviewbox.data.Household
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val textSizes = listOf(
    "Small" to 0.85f,
    "Medium" to 1f,
    "Large" to 1.2f,
    "Extra large" to 1.4f,
)

@Composable
fun SettingsScreen(
    state: BoardState,
    vm: BoardViewModel,
    onClose: () -> Unit,
    onOpenAndroidSettings: () -> Unit,
    onAddAccount: (googleOnly: Boolean) -> Unit,
    onManageAccounts: () -> Unit,
    onEditDinner: (LocalDate) -> Unit,
) {
    var token by remember { mutableStateOf(vm.currentToken()) }
    var project by remember { mutableStateOf(vm.currentProject()) }
    var place by remember { mutableStateOf(vm.currentWeatherPlace()) }
    var groceries by remember { mutableStateOf(vm.currentGroceriesProject()) }
    var staples by remember { mutableStateOf(vm.currentStaples()) }
    var showToken by remember { mutableStateOf(false) }
    val saveFields: () -> Unit = {
        vm.saveTodoist(token, project, groceries, staples)
        vm.saveWeatherPlace(place)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.White)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Settings",
                fontSize = 44.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                color = Ink.Black,
            )
            Spacer(Modifier.weight(1f))
            InkButton("Save and close", selected = true) {
                saveFields()
                onClose()
            }
        }

        Section("Shared to-do list")
        Hint("Use the Todoist account made for this display. Find its token in Todoist under Settings, Integrations, Developer.")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("API token") },
                singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.width(640.dp),
            )
            Spacer(Modifier.width(16.dp))
            InkButton(if (showToken) "Hide" else "Show") { showToken = !showToken }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = project,
            onValueChange = { project = it },
            label = { Text("Project name") },
            placeholder = { Text("Home") },
            singleLine = true,
            modifier = Modifier.width(640.dp),
        )
        Hint("Leave blank to use the first shared project on that account.")

        Section("Groceries")
        Hint("A second shared Todoist project. Share it with the display's Todoist account. Leave blank to hide the tab.")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = groceries,
            onValueChange = { groceries = it },
            label = { Text("Groceries project name") },
            singleLine = true,
            modifier = Modifier.width(640.dp),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = staples,
            onValueChange = { staples = it },
            label = { Text("Quick add items, separated by commas") },
            modifier = Modifier.width(900.dp),
        )
        Hint("These appear as one-tap buttons when adding groceries on the board.")

        Section("Weather")
        Hint("Type your town or suburb. Add the state if the name is common, e.g. Burwood, NSW.")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = place,
            onValueChange = { place = it },
            label = { Text("Town or suburb") },
            singleLine = true,
            modifier = Modifier.width(640.dp),
        )
        state.weatherPlace?.let { Hint("Showing weather for $it.") }
        Hint("Forecasts come from Open-Meteo, which is free and needs no account.")

        Section("Calendars")
        Hint("Sign in to each Google account whose calendar should show. New calendars appear here within a minute or two and are ticked automatically.")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InkButton("Add Google account", selected = true) { onAddAccount(true) }
            InkButton("Other account type") { onAddAccount(false) }
            InkButton("Manage accounts", onClick = onManageAccounts)
        }
        state.accountMessage?.let { Hint(it) }
        Spacer(Modifier.height(12.dp))
        Hint("Tick the calendars to show. Tap a marker to change how that calendar's events look.")
        if (state.calendars.isEmpty()) {
            Text(
                if (state.hasCalendarPermission) {
                    "No calendars yet. Tap Add Google account above and sign in."
                } else {
                    "Calendar access isn't allowed yet. Go back to the board and tap Allow calendar access."
                },
                fontSize = 20.sp,
                color = Ink.Black,
            )
        }
        state.calendars.forEach { cal ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = cal.id in state.shownCalendarIds,
                    onCheckedChange = { vm.setCalendarShown(cal.id, it) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Ink.Black,
                        uncheckedColor = Ink.Black,
                        checkmarkColor = Ink.White,
                    ),
                )
                Box(
                    Modifier
                        .clickable(interactionSource = null, indication = null) { vm.cycleStyle(cal.id) }
                        .padding(12.dp)
                ) {
                    Marker(styleOf(state.styles[cal.id]), 30.dp)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(cal.name, fontSize = 22.sp, color = Ink.Black)
                    val note = if (cal.syncing) cal.account else "${cal.account}, not syncing yet"
                    Text(note, fontSize = 16.sp, color = Ink.DarkGrey)
                }
            }
        }

        Section("Household")
        Hint("Names used for chores and cooking. Tap a marker to change it; matching someone's calendar marker keeps things consistent.")
        state.household.forEach { person ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .clickable(interactionSource = null, indication = null) { vm.cyclePersonStyle(person.id) }
                        .padding(10.dp)
                ) {
                    Marker(styleOf(person.style), 28.dp)
                }
                var name by remember(person.id) { mutableStateOf(person.name) }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (it.isNotBlank()) vm.renamePerson(person.id, it.trim())
                    },
                    singleLine = true,
                    modifier = Modifier.width(320.dp),
                )
                if (state.household.size > 1) {
                    Spacer(Modifier.width(12.dp))
                    InkButton("Remove", small = true) { vm.removePerson(person.id) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        InkButton("Add person", onClick = vm::addPerson)

        Section("Chores")
        ChoresSettings(state, vm)

        Section("Repeating dinners")
        val repeating = state.dinners.filter { it.repeatWeeks > 0 }
        if (repeating.isEmpty()) {
            Hint("None yet. Tap a day on the board, plan a dinner, and choose how often it repeats.")
        }
        repeating.forEach { plan ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawLine(Ink.LightGrey, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(plan.meal, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink.Black, modifier = Modifier.width(240.dp))
                Text(repeatText(plan), fontSize = 18.sp, color = Ink.DarkGrey, modifier = Modifier.width(280.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text("Cook: ", fontSize = 18.sp, color = Ink.DarkGrey)
                    when (plan.cook) {
                        COOK_TURNS -> Text("takes turns", fontSize = 18.sp, color = Ink.DarkGrey)
                        COOK_ANYONE -> PersonPill(null, null)
                        else -> state.person(plan.cook).let { PersonPill(it?.name, it?.style) }
                    }
                }
                InkButton("Edit", small = true) {
                    saveFields()
                    onEditDinner(Household.nextOccurrence(plan, state.today))
                }
                Spacer(Modifier.width(10.dp))
                InkButton("Delete", small = true) { vm.deleteDinner(plan.id) }
            }
        }
        Hint("Planning a different meal on one day replaces the repeat for that day only.")

        Section("Text size")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            textSizes.forEach { (label, scale) ->
                InkButton(label, selected = state.textScale == scale) { vm.setTextScale(scale) }
            }
        }

        Section("App updates")
        Hint("Installed: build ${state.currentBuild}" + (state.update?.let { ". Build ${it.build} is available." } ?: ""))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InkButton("Check for updates", onClick = vm::checkForUpdateNow)
            if (state.update != null) InkButton("Update now", selected = true, onClick = vm::installUpdate)
            InkButton("Open download page", onClick = vm::openReleasePage)
        }
        state.updateStatus?.let { Hint(it) }

        Section("Device")
        Hint("Use this to change the Home app or other Android settings.")
        Spacer(Modifier.height(8.dp))
        InkButton("Open Android settings", onClick = onOpenAndroidSettings)
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(32.dp))
    Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink.Black)
    Spacer(Modifier.height(4.dp))
    Box(Modifier.fillMaxWidth().height(2.dp).background(Ink.Black))
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 18.sp, color = Ink.DarkGrey, modifier = Modifier.padding(vertical = 4.dp))
}


private val weekdayShortFmt = DateTimeFormatter.ofPattern("EEE")

private fun repeatText(plan: DinnerPlan): String {
    val day = plan.start.format(weekdayShortFmt)
    return when (plan.repeatWeeks) {
        1 -> "Every week, $day"
        else -> "Every ${plan.repeatWeeks} weeks, $day"
    }
}

/** "Every day", "Wednesdays", "Mon and Thu", "Mon, Wed and Fri". */
fun daysText(days: Set<DayOfWeek>): String {
    if (days.isEmpty() || days.size == 7) return "Every day"
    val sorted = days.sortedBy { it.value }
    if (sorted.size == 1) return sorted[0].getDisplayName(TextStyle.FULL, Locale.getDefault()) + "s"
    val names = sorted.map { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
    return names.dropLast(1).joinToString(", ") + " and " + names.last()
}

@Composable
private fun ChoresSettings(state: BoardState, vm: BoardViewModel) {
    var editing by remember { mutableStateOf<String?>(null) } // a chore id, or NEW_CHORE
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            if (state.chores.isEmpty()) {
                Hint("No chores yet. Add one, pick its days and who shares it.")
            }
            state.chores.forEach { chore ->
                val selected = editing == chore.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (selected) Ink.LightGrey.copy(alpha = 0.35f) else Ink.White)
                        .clickable(interactionSource = null, indication = null) { editing = chore.id }
                        .drawBehind {
                            drawLine(Ink.LightGrey, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                        }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(chore.name, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink.Black, modifier = Modifier.width(190.dp))
                    Text(daysText(chore.days), fontSize = 18.sp, color = Ink.DarkGrey, modifier = Modifier.width(200.dp))
                    Text(
                        chore.people.mapNotNull { state.person(it)?.name }.joinToString(" and "),
                        fontSize = 18.sp,
                        color = Ink.DarkGrey,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text("Next: ", fontSize = 16.sp, color = Ink.DarkGrey)
                    val next = state.person(chore.personAt(chore.nextIndex))
                    PersonPill(next?.name, next?.style)
                }
            }
            Spacer(Modifier.height(16.dp))
            InkButton("Add chore") { editing = NEW_CHORE }
        }
        editing?.let { id ->
            Spacer(Modifier.width(32.dp))
            ChoreEditor(
                chore = state.chores.firstOrNull { it.id == id },
                state = state,
                onSave = { name, days, people, next ->
                    vm.saveChore(if (id == NEW_CHORE) null else id, name, days, people, next)
                    editing = null
                },
                onDelete = {
                    vm.deleteChore(id)
                    editing = null
                },
                onCancel = { editing = null },
            )
        }
    }
}

private const val NEW_CHORE = "new"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoreEditor(
    chore: Chore?,
    state: BoardState,
    onSave: (String, Set<DayOfWeek>, List<String>, String?) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val key = chore?.id ?: NEW_CHORE
    var name by remember(key) { mutableStateOf(chore?.name ?: "") }
    var days by remember(key) { mutableStateOf(chore?.days ?: emptySet()) }
    var sharers by remember(key) { mutableStateOf(chore?.people ?: state.household.map { it.id }) }
    var next by remember(key) { mutableStateOf(chore?.let { it.personAt(it.nextIndex) } ?: sharers.firstOrNull()) }
    var problem by remember(key) { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .width(640.dp)
            .border(2.dp, Ink.Black, RoundedCornerShape(12.dp))
            .padding(horizontal = 26.dp, vertical = 22.dp)
    ) {
        Text(if (chore == null) "New chore" else "Edit chore", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink.Black)
        Text("Name", fontSize = 17.sp, color = Ink.DarkGrey, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            placeholder = { Text("e.g. Bins") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Which days", fontSize = 17.sp, color = Ink.DarkGrey, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InkChip("Every day", days.isEmpty()) { days = emptySet() }
            DayOfWeek.values().forEach { d ->
                InkChip(d.getDisplayName(TextStyle.SHORT, Locale.getDefault()), d in days) {
                    days = if (d in days) days - d else days + d
                }
            }
        }
        Text("Who shares it", fontSize = 17.sp, color = Ink.DarkGrey, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.household.forEach { p ->
                InkChip(p.name, p.id in sharers) {
                    // Keep household order so turns go round predictably.
                    sharers = if (p.id in sharers) sharers - p.id
                    else state.household.map { it.id }.filter { it in sharers || it == p.id }
                    if (next !in sharers) next = sharers.firstOrNull()
                }
            }
        }
        Text("Up next", fontSize = 17.sp, color = Ink.DarkGrey, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sharers.forEach { id ->
                val p = state.person(id)
                InkChip(p?.name ?: "?", next == id) { next = id }
            }
        }
        Text(
            "Turns move on when it's ticked off on the board.",
            fontSize = 16.sp,
            color = Ink.DarkGrey,
            modifier = Modifier.padding(top = 10.dp),
        )
        problem?.let { Text(it, fontSize = 17.sp, color = Ink.Black, modifier = Modifier.padding(top = 8.dp)) }
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InkButton("Save chore", selected = true) {
                when {
                    name.isBlank() -> problem = "Give it a name."
                    sharers.isEmpty() -> problem = "Pick at least one person."
                    else -> onSave(name, days, sharers, next)
                }
            }
            if (chore != null) InkButton("Delete", onClick = onDelete)
            InkButton("Cancel", onClick = onCancel)
        }
    }
}
