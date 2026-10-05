package com.calendarviewbox.data

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

data class CalendarInfo(
    val id: Long,
    val name: String,
    val account: String,
    val accountType: String,
    val visible: Boolean,
    val syncing: Boolean,
)

data class EventItem(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val location: String?,
    val description: String? = null,
    val allDay: Boolean,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val startDate: LocalDate,
    val endDateInclusive: LocalDate,
)

/**
 * Reads the calendars Android has synced to this device (your Google accounts).
 * No Google API keys needed: the system calendar provider already holds the data.
 */
class CalendarRepository(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    fun calendars(): List<CalendarInfo> {
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.VISIBLE,
            Calendars.SYNC_EVENTS,
        )
        val out = mutableListOf<CalendarInfo>()
        resolver.query(
            Calendars.CONTENT_URI, projection, null, null,
            "${Calendars.ACCOUNT_NAME} ASC, ${Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                out += CalendarInfo(
                    id = c.getLong(0),
                    name = c.getString(1) ?: "Calendar",
                    account = c.getString(2) ?: "",
                    accountType = c.getString(3) ?: "",
                    visible = c.getInt(4) == 1,
                    syncing = c.getInt(5) == 1,
                )
            }
        }
        return out
    }

    fun events(from: LocalDate, toExclusive: LocalDate, calendarIds: Set<Long>): List<EventItem> {
        if (calendarIds.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val day = 24L * 60 * 60 * 1000
        // Pad a day each side: all-day events are stored at UTC midnight.
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli() - day
        val end = toExclusive.atStartOfDay(zone).toInstant().toEpochMilli() + day

        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID,
            Instances.CALENDAR_ID,
            Instances.TITLE,
            Instances.EVENT_LOCATION,
            Instances.ALL_DAY,
            Instances.BEGIN,
            Instances.END,
            Instances.STATUS,
            Instances.SELF_ATTENDEE_STATUS,
            Instances.DESCRIPTION,
        )

        val out = mutableListOf<EventItem>()
        resolver.query(uri, projection, null, null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val calendarId = c.getLong(1)
                if (calendarId !in calendarIds) continue
                if (!c.isNull(7) && c.getInt(7) == Events.STATUS_CANCELED) continue
                if (!c.isNull(8) && c.getInt(8) == Attendees.ATTENDEE_STATUS_DECLINED) continue

                val allDay = c.getInt(4) == 1
                val b = c.getLong(5)
                val e = if (c.isNull(6)) b else c.getLong(6)

                val item = if (allDay) {
                    val s = Instant.ofEpochMilli(b).atZone(ZoneOffset.UTC).toLocalDate()
                    var last = Instant.ofEpochMilli(e).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1)
                    if (last.isBefore(s)) last = s
                    EventItem(
                        c.getLong(0), calendarId, title(c.getString(2)), c.getString(3), c.getString(9),
                        true, s.atStartOfDay(), last.atStartOfDay(), s, last,
                    )
                } else {
                    val s = Instant.ofEpochMilli(b).atZone(zone).toLocalDateTime()
                    val en = Instant.ofEpochMilli(e).atZone(zone).toLocalDateTime()
                    // An event ending exactly at midnight doesn't spill into the next day.
                    val last = if (en.toLocalTime() == LocalTime.MIDNIGHT && en.toLocalDate().isAfter(s.toLocalDate())) {
                        en.toLocalDate().minusDays(1)
                    } else {
                        if (en.toLocalDate().isAfter(s.toLocalDate())) en.toLocalDate() else s.toLocalDate()
                    }
                    EventItem(
                        c.getLong(0), calendarId, title(c.getString(2)), c.getString(3), c.getString(9),
                        false, s, en, s.toLocalDate(), last,
                    )
                }
                if (item.endDateInclusive.isBefore(from) || !item.startDate.isBefore(toExclusive)) continue
                out += item
            }
        }
        return out
    }

    /** Turns syncing on for a calendar (e.g. one your partner shared) so its events reach this device. */
    fun enableSync(calendarId: Long) {
        val values = ContentValues().apply {
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.VISIBLE, 1)
        }
        resolver.update(ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId), values, null, null)
    }

    /** Asks Android to sync calendar accounts now. Best effort: some devices ignore it. */
    fun requestSync() {
        val accounts = runCatching { calendars() }.getOrDefault(emptyList())
            .filter { it.account.isNotBlank() && it.accountType.isNotBlank() }
            .map { Account(it.account, it.accountType) }
            .distinct()
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        // A null account asks every account to sync, which also covers one that was just added.
        runCatching { ContentResolver.requestSync(null, CalendarContract.AUTHORITY, extras) }
        accounts.forEach { account ->
            runCatching { ContentResolver.requestSync(account, CalendarContract.AUTHORITY, extras) }
        }
    }

    private fun title(raw: String?): String = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: "(No title)"
}
