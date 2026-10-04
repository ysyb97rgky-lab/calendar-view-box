package com.calendarviewbox.ui

import androidx.compose.foundation.background
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
) {
    var token by remember { mutableStateOf(vm.currentToken()) }
    var project by remember { mutableStateOf(vm.currentProject()) }
    var place by remember { mutableStateOf(vm.currentWeatherPlace()) }
    var groceries by remember { mutableStateOf(vm.currentGroceriesProject()) }
    var staples by remember { mutableStateOf(vm.currentStaples()) }
    var showToken by remember { mutableStateOf(false) }

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
                vm.saveTodoist(token, project, groceries, staples)
                vm.saveWeatherPlace(place)
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
