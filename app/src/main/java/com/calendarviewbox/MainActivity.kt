package com.calendarviewbox

import android.Manifest
import android.accounts.AccountManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
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

        if (!hasCalendarPermission()) requestCalendarPermission()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        vm.onResume()
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
