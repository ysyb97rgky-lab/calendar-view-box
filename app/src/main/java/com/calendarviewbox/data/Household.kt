package com.calendarviewbox.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Someone in the household. [style] picks their greyscale marker, like a calendar's. */
data class Person(val id: String, val name: String, val style: Int)

/**
 * A chore that rotates between [people]. Turns move on only when it's ticked off,
 * so a missed chore stays with the same person until it's done.
 */
data class Chore(
    val id: String,
    val name: String,
    /** Days it's due. Empty means every day. */
    val days: Set<DayOfWeek>,
    /** Person ids, in turn order. */
    val people: List<String>,
    /** Index into [people] of whoever is up next. */
    val nextIndex: Int,
    val createdOn: LocalDate,
    val lastDone: LocalDate? = null,
    val lastDoneBy: String? = null,
    val lastDoneAt: Long? = null,
    // Lets a same-day tick be undone.
    val undoNextIndex: Int? = null,
    val undoLastDone: LocalDate? = null,
    val undoLastDoneBy: String? = null,
) {
    fun isScheduled(date: LocalDate): Boolean = days.isEmpty() || date.dayOfWeek in days
    fun personAt(index: Int): String? = if (people.isEmpty()) null else people[Math.floorMod(index, people.size)]
}

const val COOK_ANYONE = "anyone"
const val COOK_TURNS = "turns"

/** A planned dinner. With [repeatWeeks] of 0 it's for [start] only; otherwise it repeats on that weekday. */
data class DinnerPlan(
    val id: String,
    val meal: String,
    /** A person id, [COOK_ANYONE] or [COOK_TURNS]. */
    val cook: String,
    val repeatWeeks: Int,
    val start: LocalDate,
    /** Who takes turns cooking, when [cook] is [COOK_TURNS]. */
    val turns: List<String> = emptyList(),
    /** Days the repeat is skipped. */
    val skips: Set<LocalDate> = emptySet(),
) {
    fun appliesOn(date: LocalDate): Boolean {
        if (repeatWeeks <= 0) return date == start
        if (date.isBefore(start) || date in skips) return false
        return ChronoUnit.DAYS.between(start, date) % (7L * repeatWeeks) == 0L
    }
}

enum class ChoreState { DONE, DUE, OVERDUE, UPCOMING }

/** How a chore appears on a given day: whose turn, and whether it's done, due, late or coming up. */
data class ChoreShown(val chore: Chore, val personId: String?, val state: ChoreState, val since: LocalDate?)

/** The dinner for a day and who's cooking (null means anyone). */
data class DinnerShown(val plan: DinnerPlan, val meal: String, val cookId: String?)

data class MealCount(val name: String, val count: Int, val lastUsed: Long)

object Household {

    fun newId(): String = UUID.randomUUID().toString()

    val defaultPeople = listOf(Person("p_you", "You", 0), Person("p_wife", "Wife", 1))

    // ---------------- dinners ----------------

    /** A one-off plan for the day wins over a repeating one. */
    fun dinnerFor(date: LocalDate, plans: List<DinnerPlan>): DinnerShown? {
        val plan = plans.lastOrNull { it.repeatWeeks == 0 && it.start == date }
            ?: plans.firstOrNull { it.repeatWeeks > 0 && it.appliesOn(date) }
            ?: return null
        return DinnerShown(plan, plan.meal, cookFor(plan, date))
    }

    private fun cookFor(plan: DinnerPlan, date: LocalDate): String? = when (plan.cook) {
        COOK_ANYONE -> null
        COOK_TURNS -> {
            if (plan.turns.isEmpty() || plan.repeatWeeks <= 0) {
                null
            } else {
                val occurrence = (ChronoUnit.DAYS.between(plan.start, date) / (7L * plan.repeatWeeks)).toInt()
                plan.turns[Math.floorMod(occurrence, plan.turns.size)]
            }
        }
        else -> plan.cook
    }

    /** First day on or after [from] that a repeating plan lands on. */
    fun nextOccurrence(plan: DinnerPlan, from: LocalDate): LocalDate {
        if (plan.repeatWeeks <= 0) return plan.start
        var d = if (from.isBefore(plan.start)) plan.start else from
        repeat(7 * plan.repeatWeeks * 3) {
            if (plan.appliesOn(d)) return d
            d = d.plusDays(1)
        }
        return plan.start
    }

    // ---------------- chores ----------------

    private fun firstScheduled(chore: Chore, from: LocalDate, until: LocalDate): LocalDate? {
        var d = from
        while (!d.isAfter(until)) {
            if (chore.isScheduled(d)) return d
            d = d.plusDays(1)
        }
        return null
    }

    private fun countScheduled(chore: Chore, from: LocalDate, toInclusive: LocalDate): Int {
        var n = 0
        var d = from
        while (!d.isAfter(toInclusive)) {
            if (chore.isScheduled(d)) n++
            d = d.plusDays(1)
        }
        return n
    }

    /**
     * Chores to show on [date]. Today shows what's done, due or overdue; later days show who's
     * expected to be up, assuming each turn gets done on time. Past days show nothing.
     */
    fun choresFor(date: LocalDate, today: LocalDate, chores: List<Chore>): List<ChoreShown> {
        if (date.isBefore(today)) return emptyList()
        val out = mutableListOf<ChoreShown>()
        for (chore in chores) {
            if (chore.people.isEmpty()) continue
            val after = chore.lastDone ?: chore.createdOn.minusDays(1)
            // The turn currently waiting to be done, if it has come round yet.
            val pending = firstScheduled(chore, after.plusDays(1), today)
            val doneToday = chore.lastDone == today
            if (date == today) {
                when {
                    doneToday -> out += ChoreShown(chore, chore.lastDoneBy, ChoreState.DONE, null)
                    pending != null -> {
                        val late = pending.isBefore(today)
                        out += ChoreShown(
                            chore,
                            chore.personAt(chore.nextIndex),
                            if (late) ChoreState.OVERDUE else ChoreState.DUE,
                            if (late) pending else null,
                        )
                    }
                }
            } else if (chore.isScheduled(date)) {
                // The waiting turn uses up today's slot, so later days move one person along.
                val start = chore.nextIndex + if (!doneToday && pending != null) 1 else 0
                val between = countScheduled(chore, today.plusDays(1), date.minusDays(1))
                out += ChoreShown(chore, chore.personAt(start + between), ChoreState.UPCOMING, null)
            }
        }
        return out
    }

    /** Ticks a chore off. The person after whoever did it is up next. */
    fun complete(chore: Chore, doerId: String?, today: LocalDate, now: Long): Chore {
        if (chore.people.isEmpty()) return chore
        val assigned = Math.floorMod(chore.nextIndex, chore.people.size)
        val doerIndex = doerId?.let { chore.people.indexOf(it) } ?: -1
        val next = if (doerIndex >= 0) doerIndex + 1 else assigned + 1
        return chore.copy(
            nextIndex = Math.floorMod(next, chore.people.size),
            lastDone = today,
            lastDoneBy = doerId ?: chore.people[assigned],
            lastDoneAt = now,
            undoNextIndex = chore.nextIndex,
            undoLastDone = chore.lastDone,
            undoLastDoneBy = chore.lastDoneBy,
        )
    }

    /** Reverses a tick made by mistake today. */
    fun undo(chore: Chore): Chore = chore.copy(
        nextIndex = chore.undoNextIndex ?: chore.nextIndex,
        lastDone = chore.undoLastDone,
        lastDoneBy = chore.undoLastDoneBy,
        lastDoneAt = null,
        undoNextIndex = null,
        undoLastDone = null,
        undoLastDoneBy = null,
    )

    // ---------------- saving ----------------

    fun peopleToJson(people: List<Person>): String = JSONArray().apply {
        people.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("style", it.style)) }
    }.toString()

    fun peopleFromJson(s: String?): List<Person>? = runCatching {
        val arr = JSONArray(s ?: return null)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Person(o.getString("id"), o.getString("name"), o.optInt("style"))
        }
    }.getOrNull()

    private fun JSONObject.dateOrNull(key: String): LocalDate? =
        if (!has(key) || isNull(key)) null else LocalDate.parse(getString(key))

    private fun JSONObject.intOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else getInt(key)

    private fun JSONObject.stringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)

    private fun strings(arr: JSONArray?): List<String> =
        if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }

    fun choresToJson(chores: List<Chore>): String = JSONArray().apply {
        chores.forEach { c ->
            put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("days", JSONArray(c.days.map { it.name }))
                    .put("people", JSONArray(c.people))
                    .put("next", c.nextIndex)
                    .put("created", c.createdOn.toString())
                    .put("lastDone", c.lastDone?.toString() ?: JSONObject.NULL)
                    .put("lastDoneBy", c.lastDoneBy ?: JSONObject.NULL)
                    .put("lastDoneAt", c.lastDoneAt ?: JSONObject.NULL)
                    .put("undoNext", c.undoNextIndex ?: JSONObject.NULL)
                    .put("undoLastDone", c.undoLastDone?.toString() ?: JSONObject.NULL)
                    .put("undoLastDoneBy", c.undoLastDoneBy ?: JSONObject.NULL)
            )
        }
    }.toString()

    fun choresFromJson(s: String?): List<Chore> = runCatching {
        val arr = JSONArray(s ?: return emptyList())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Chore(
                id = o.getString("id"),
                name = o.getString("name"),
                days = strings(o.optJSONArray("days")).mapNotNull { d -> runCatching { DayOfWeek.valueOf(d) }.getOrNull() }.toSet(),
                people = strings(o.optJSONArray("people")),
                nextIndex = o.optInt("next"),
                createdOn = o.dateOrNull("created") ?: LocalDate.now(),
                lastDone = o.dateOrNull("lastDone"),
                lastDoneBy = o.stringOrNull("lastDoneBy"),
                lastDoneAt = if (!o.has("lastDoneAt") || o.isNull("lastDoneAt")) null else o.getLong("lastDoneAt"),
                undoNextIndex = o.intOrNull("undoNext"),
                undoLastDone = o.dateOrNull("undoLastDone"),
                undoLastDoneBy = o.stringOrNull("undoLastDoneBy"),
            )
        }
    }.getOrDefault(emptyList())

    fun dinnersToJson(plans: List<DinnerPlan>): String = JSONArray().apply {
        plans.forEach { p ->
            put(
                JSONObject()
                    .put("id", p.id)
                    .put("meal", p.meal)
                    .put("cook", p.cook)
                    .put("repeat", p.repeatWeeks)
                    .put("start", p.start.toString())
                    .put("turns", JSONArray(p.turns))
                    .put("skips", JSONArray(p.skips.map { it.toString() }))
            )
        }
    }.toString()

    fun dinnersFromJson(s: String?): List<DinnerPlan> = runCatching {
        val arr = JSONArray(s ?: return emptyList())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            DinnerPlan(
                id = o.getString("id"),
                meal = o.getString("meal"),
                cook = o.optString("cook", COOK_ANYONE),
                repeatWeeks = o.optInt("repeat"),
                start = LocalDate.parse(o.getString("start")),
                turns = strings(o.optJSONArray("turns")),
                skips = strings(o.optJSONArray("skips")).map { d -> LocalDate.parse(d) }.toSet(),
            )
        }
    }.getOrDefault(emptyList())

    fun mealsToJson(meals: List<MealCount>): String = JSONArray().apply {
        meals.forEach { put(JSONObject().put("name", it.name).put("count", it.count).put("last", it.lastUsed)) }
    }.toString()

    fun mealsFromJson(s: String?): List<MealCount> = runCatching {
        val arr = JSONArray(s ?: return emptyList())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            MealCount(o.getString("name"), o.optInt("count", 1), o.optLong("last"))
        }
    }.getOrDefault(emptyList())
}
