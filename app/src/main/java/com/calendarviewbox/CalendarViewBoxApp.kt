package com.calendarviewbox

import android.app.Application
import android.content.Context

/**
 * Records any crash so the next launch can show what went wrong (as plain text),
 * instead of crashing again with no explanation.
 */
class CalendarViewBoxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE).edit()
                    .putString("trace", error.stackTraceToString().take(12_000))
                    .putLong("at", System.currentTimeMillis())
                    .putInt("build", BuildConfig.VERSION_CODE)
                    .commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_PREFS = "crash"
    }
}
