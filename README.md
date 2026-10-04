# Calendar View Box

A full-screen family calendar and shared to-do list for a Boox Note Max (or any Android e-ink tablet), hung in landscape.

- **Left:** today's date, the weather, and two shared Todoist lists on tabs: To do and Groceries. Tap a circle to tick an item off, or tap Add to type a new one. Groceries has one-tap buttons for staples, and the board returns to To do after 5 minutes.
- **Right:** your Google calendars as Week, 2 weeks, Month or Agenda. The choice is remembered. On Saturday and Sunday the Week view starts from today, so the coming week is in view.
- Black, white and greys only. Each calendar gets its own marker (outlined, light grey, black or dark grey), shown in the legend at the bottom.
- Calendar changes show within a minute or so of syncing. The to-do list checks Todoist every 30 seconds.
- If the connection drops for more than a minute, a black "Offline since..." note replaces the "List updated" line. The last list and forecast stay on screen, even after a restart.
- Weather comes from Open-Meteo, which is free and needs no account. It updates every 30 minutes.
- Once an hour the screen flashes black then white to clear e-ink ghosting.
- When a newer build is on the GitHub Releases page, an **Update** button appears next to Refresh. One tap downloads it and Android asks you to confirm.

## 1. Accounts (do these first)

### Google Calendar

Sign in on the Boox itself. In the app, open **Settings** and tap **Add Google account**, then sign in. Repeat for each person's account (your wife will likely need to approve the sign-in on her phone). Calendars arrive within a minute or two and are ticked automatically. If the board has no calendars yet, the same button sits at the bottom of the calendar.

- If Google sign-in isn't offered, turn on Google Play in the Boox settings first.
- To keep personal accounts on the wall tidy, open **Manage accounts** and switch off everything except Calendar for each account.
- If your calendar is still in iCloud, move it to Google first, or use the DAVx5 app with an Apple app-specific password.

Prefer not to put personal accounts on the Boox? Share your calendars with one Google account made just for the display and sign in with that instead.

### Todoist (the shared to-do list)
1. On your phone, create a project called `Home` and share it with your wife. Do the same for a project called `Groceries`. Sections in Groceries (e.g. Fruit and veg, Dairy) show as headings on the board.
2. Create a Todoist account for the display (the display Gmail works) and share both projects with it too.
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

### Updating

Builds made by GitHub (the `build-N` releases) check for newer builds every 6 hours and when you tap Refresh. The first time you tap **Update**, Android asks you to allow installs from Calendar View Box; allow it, go back and tap Update again. The app downloads the new build and opens Android's standard "update this app?" screen, the same one used when installing from the browser. Each update keeps your settings, since every build uses the same signing key. After it installs, reopen the app (or press Home if it's your Home app). If anything goes wrong, Settings has **Open download page**, which opens the Releases page in the browser.

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
- **Settings** lets you pick which calendars show, tap a calendar's marker to change its style, set the Todoist token and project names, edit the grocery quick-add items, set the weather location, check for updates, and change text size.
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
  data/Updater             checks GitHub Releases, downloads and installs new builds
  data/Prefs               saved settings and date ranges
  ui/Board.kt              layout, to-do pane, toolbar, legend
  ui/CalendarViews.kt      week, 2 weeks, month, agenda
  ui/SettingsScreen.kt     settings
  ui/WeatherViews.kt       weather strip and greyscale icons
  ui/Theme.kt              greyscale palette and marker styles
```
