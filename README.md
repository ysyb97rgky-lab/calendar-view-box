# Calendar View Box

A full-screen family calendar and shared to-do list for a Boox Note Max (or any Android e-ink tablet), hung in landscape.

- **Left:** today's date, the weather, and the shared Todoist list. Tap a circle to tick an item off, or tap Add to type a new one.
- **Right:** your Google calendars as Week, 2 weeks, Month or Agenda. The choice is remembered. On Saturday and Sunday the Week view starts from today, so the coming week is in view.
- Black, white and greys only. Each calendar gets its own marker (outlined, light grey, black or dark grey), shown in the legend at the bottom.
- Calendar changes show within a minute or so of syncing. The to-do list checks Todoist every 30 seconds.
- If the connection drops for more than a minute, a black "Offline since..." note replaces the "List updated" line. The last list and forecast stay on screen, even after a restart.
- Weather comes from Open-Meteo, which is free and needs no account. It updates every 30 minutes.
- Once an hour the screen flashes black then white to clear e-ink ghosting.

## 1. Accounts (do these first)

### Google Calendar
1. Create a new Google account just for the display, for example `ourfamily.display@gmail.com`.
2. You and your wife each share your calendar with that address. In Google Calendar on a computer: Settings, pick your calendar under "Settings for my calendars", then "Share with specific people", add the display address with **See all event details**.
3. Sign in to the display account in a browser, open each share email and add the calendar.
4. Optional: create a "Family" calendar for joint events and share it with each other and with the display account.

If your calendar is still in iCloud, move it to Google first. On the iPhone, add your Google account in the Calendar settings and set Google as the default calendar.

### Todoist (the shared to-do list)
1. On your phone, create a project called `Home` and share it with your wife.
2. Create a Todoist account for the display (the display Gmail works) and share `Home` with it too.
3. Sign in to that display account at todoist.com, accept the invite, then go to Settings, Integrations, Developer and copy the **API token**.

Giving the display its own Todoist account means the token on the wall can only see the `Home` list.

## 2. Build and install

You need a computer with [Android Studio](https://developer.android.com/studio).

1. Unzip this folder and open it in Android Studio (File, Open). Let it finish syncing. If it offers to upgrade the Android Gradle Plugin, you can skip that.
2. Open `local.properties` in the project root (Android Studio creates it) and add:
   ```
   todoist.token=PASTE_TOKEN_HERE
   todoist.project=Home
   weather.place=Sydney, NSW
   ```
   You can also type these into the app's Settings screen later. For the weather, use your town or suburb, adding the state if the name is common.
3. Install on the Boox, using either method:
   - **USB:** turn on USB debugging on the Boox (search the Boox settings for "developer" or "USB debugging"), plug it in, pick it in Android Studio's device list and press Run.
   - **APK file:** in Android Studio choose Build, Build App Bundle(s) / APK(s), Build APK(s). Copy `app/build/outputs/apk/debug/app-debug.apk` to the Boox (BOOXDrop or USB) and tap it to install. Allow installs from unknown sources when asked.
4. On the Boox, sign in to the **display** Google account in Android settings (Accounts) and let it sync.
5. Open Calendar View Box and allow calendar access.

## 3. Boox settings

Boox moves these between firmware versions. If a name doesn't match, search the settings for the key word.

- **Sleep:** set auto sleep and power-off timeout to Never, and keep the Boox on its charger.
- **Freezing:** in the app library, long-press Calendar View Box, open its app settings and turn off freezing / allow background activity. Otherwise the list stops updating.
- **Refresh mode:** in the E-Ink Center, set this app to the sharpest (HD) mode. The screen rarely changes, so speed doesn't matter.
- **Home app (optional):** if your firmware lets you choose a default Home app, pick Calendar View Box so it comes back on its own after a restart. Settings in the app has a button to reach Android settings if you want to switch back.

## 4. Using it

- **Week / 2 weeks / Month / Agenda** buttons switch the view.
- **Refresh** asks Android to sync calendars, reloads the to-do list and weather, and redraws the screen.
- **Add** (next to "To do") opens the keyboard. The item goes into the shared Todoist project and shows on both phones. Dates typed here aren't read as due dates, so add dated items from a phone.
- **Settings** lets you pick which calendars show, tap a calendar's marker to change its style, set the Todoist token and project, set the weather location, and change text size.
- Back does nothing on the main board, so a stray tap won't close it.

## Troubleshooting

- **A shared calendar is missing:** open Settings and tick it. The app turns on syncing for that calendar. If it still isn't listed, check the share was accepted on the display account (step 1.3), then tap Refresh and give it a few minutes.
- **"Todoist error 401 / token was rejected":** the token is wrong or was regenerated. Copy it again from the display account.
- **"No Todoist project called Home":** the display account hasn't accepted the share, or the name differs. Leave the project name blank to use the first shared project.
- **Text too small or too large:** Settings, Text size.
- **Ghosting:** tap Refresh, or wait for the hourly flash.
- **"Offline since..." won't clear:** check the Boox's Wi-Fi, and that Boox freezing and sleep are off for the app. The note clears on the next successful sync.
- **Weather shows the wrong place:** in Settings, add the state after the name (e.g. "Burwood, NSW" rather than "Burwood").

## Project layout

```
app/src/main/java/com/calendarviewbox/
  MainActivity.kt          full screen, keep awake, permissions
  BoardViewModel.kt        state, refresh loops, actions
  data/CalendarRepository  reads Android's calendar provider
  data/TodoistClient       Todoist API v1 (list projects, list, add and close tasks)
  data/WeatherClient       Open-Meteo place search and forecast
  data/Http                small HTTP helper, tells offline apart from server errors
  data/Prefs               saved settings and date ranges
  ui/Board.kt              layout, to-do pane, toolbar, legend
  ui/CalendarViews.kt      week, 2 weeks, month, agenda
  ui/SettingsScreen.kt     settings
  ui/WeatherViews.kt       weather strip and greyscale icons
  ui/Theme.kt              greyscale palette and marker styles
```
