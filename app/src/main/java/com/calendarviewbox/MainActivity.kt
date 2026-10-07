package com.calendarviewbox

import android.Manifest
import android.accounts.AccountManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.calendarviewbox.ui.App

class MainActivity : ComponentActivity() {

    private val vm: BoardViewModel by viewModels()
    private var boardStarted = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            vm.onPermissionResult()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stay on: this is a wall display.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        // If the app crashed in the last 10 minutes, show what happened instead of looping.
        val crash = getSharedPreferences(CalendarViewBoxApp.CRASH_PREFS, MODE_PRIVATE)
        val trace = crash.getString("trace", null)
        val crashedAt = crash.getLong("at", 0L)
        if (trace != null && System.currentTimeMillis() - crashedAt < 10 * 60_000L) {
            showCrashReport(trace, crash.getInt("build", 0))
            return
        }
        startBoard()
    }

    private fun startBoard() {
        boardStarted = true
        setContent {
            App(
                vm = vm,
                onRequestPermission = ::requestCalendarPermission,
                onOpenAndroidSettings = {
                    startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                onAddAccount = ::addCalendarAccount,
                onManageAccounts = ::manageAccounts,
            )
        }

        // Store pages for grocery prices load here, behind the board, where Android treats them as visible.
        val content = findViewById<ViewGroup>(android.R.id.content)
        val host = FrameLayout(this)
        content.addView(host, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        com.calendarviewbox.data.WebHost.container = host

        if (!hasCalendarPermission()) requestCalendarPermission()
    }

    override fun onDestroy() {
        com.calendarviewbox.data.WebHost.container = null
        super.onDestroy()
    }

    /** Plain Android views (no Compose), so this screen works even if the board can't draw. */
    private fun showCrashReport(trace: String, build: Int) {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(TextView(this).apply {
            text = "Calendar View Box stopped (build $build). Take a photo of this screen and send it over."
            textSize = 22f
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Try again"
            setOnClickListener {
                getSharedPreferences(CalendarViewBoxApp.CRASH_PREFS, MODE_PRIVATE).edit().clear().commit()
                recreate()
            }
        })
        layout.addView(buttons)
        // The most useful lines first: the error and anything from this app's code.
        val lines = trace.lines()
        val key = lines.filter { it.contains("Exception") || it.contains("Error") || it.contains("com.calendarviewbox") }
        val shown = (key.take(25) + listOf("", "Full trace:") + lines.take(60)).joinToString("\n")
        layout.addView(ScrollView(this).apply {
            addView(TextView(this@MainActivity).apply {
                text = shown
                textSize = 15f
                setTextColor(Color.BLACK)
                typeface = Typeface.MONOSPACE
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (boardStarted) vm.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCalendarPermission() {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        )
    }

    /** Opens Android's sign-in screen. With [googleOnly] it goes straight to Google sign-in. */
    private fun addCalendarAccount(googleOnly: Boolean) {
        vm.showAccountMessage(null)
        val hasGoogle = runCatching {
            AccountManager.get(this).authenticatorTypes.any { it.type == "com.google" }
        }.getOrDefault(false)
        if (googleOnly && !hasGoogle) {
            vm.showAccountMessage(
                "Google sign-in isn't available on this Boox yet. Turn on Google Play in the Boox settings, then try again."
            )
            return
        }
        val intent = Intent(Settings.ACTION_ADD_ACCOUNT)
        if (googleOnly) intent.putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
        runCatching { startActivity(intent) }.onFailure { manageAccounts() }
    }

    /** Android's account list, for removing an account or switching calendar sync on. */
    private fun manageAccounts() {
        runCatching { startActivity(Intent(Settings.ACTION_SYNC_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
