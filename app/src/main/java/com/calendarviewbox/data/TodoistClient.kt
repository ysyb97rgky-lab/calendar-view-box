package com.calendarviewbox.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class TodoTask(
    val id: String,
    val content: String,
    val parentId: String?,
    val order: Int,
    val due: LocalDate?,
    val dueTime: LocalTime?,
    val isRecurring: Boolean,
    val sectionId: String? = null,
)

/** A Todoist section, e.g. "Fruit and veg" in the Groceries project. */
data class TodoSection(val id: String, val name: String, val order: Int)

/** Minimal client for the Todoist API v1 (https://developer.todoist.com/api/v1/). */
class TodoistClient(private val token: String) {

    private val base = "https://api.todoist.com/api/v1"

    /**
     * Finds a project by name (case-insensitive). With a blank name and [fallbackToShared],
     * picks the first shared project, then the first project that isn't the Inbox.
     */
    suspend fun findProjectId(name: String, fallbackToShared: Boolean = true): String? = withContext(Dispatchers.IO) {
        val projects = getAll("$base/projects")
            .filterNot { it.optBoolean("is_deleted") || it.optBoolean("is_archived") }
        if (name.isNotBlank()) {
            projects.firstOrNull { it.optString("name").equals(name.trim(), ignoreCase = true) }
                ?.optString("id")
        } else if (!fallbackToShared) {
            null
        } else {
            val candidates = projects.filterNot { it.optBoolean("inbox_project") }
            (candidates.firstOrNull { it.optBoolean("shared") } ?: candidates.firstOrNull())
                ?.optString("id")
        }
    }

    suspend fun tasks(projectId: String): List<TodoTask> = withContext(Dispatchers.IO) {
        getAll("$base/tasks?project_id=${enc(projectId)}")
            .filterNot { it.optBoolean("checked") || it.optBoolean("is_deleted") }
            .map { parseTask(it) }
    }

    suspend fun sections(projectId: String): List<TodoSection> = withContext(Dispatchers.IO) {
        getAll("$base/sections?project_id=${enc(projectId)}")
            .filterNot { it.optBoolean("is_deleted") || it.optBoolean("is_archived") }
            .map {
                TodoSection(
                    id = it.optString("id"),
                    name = it.optString("name"),
                    order = it.optInt("section_order", it.optInt("order", 0)),
                )
            }
    }

    suspend fun close(taskId: String) {
        withContext(Dispatchers.IO) { request("POST", "$base/tasks/${enc(taskId)}/close") }
    }

    /** Adds a plain item to the project. Dates typed here aren't parsed; add those from the phone. */
    /** Returns the new task's id, when Todoist sends it back. */
    suspend fun addTask(content: String, projectId: String): String? {
        val body = JSONObject().put("content", content).put("project_id", projectId).toString()
        val response = withContext(Dispatchers.IO) { request("POST", "$base/tasks", body) }
        return runCatching { JSONObject(response).optString("id").takeIf { it.isNotBlank() } }.getOrNull()
    }

    private fun parseTask(o: JSONObject): TodoTask {
        var date: LocalDate? = null
        var time: LocalTime? = null
        var recurring = false
        val due = o.optJSONObject("due")
        if (due != null) {
            recurring = due.optBoolean("is_recurring")
            val raw = if (due.isNull("date")) "" else due.optString("date")
            runCatching {
                when {
                    raw.isEmpty() -> Unit
                    raw.length <= 10 -> date = LocalDate.parse(raw)
                    raw.endsWith("Z") -> {
                        val local = Instant.parse(raw).atZone(ZoneId.systemDefault()).toLocalDateTime()
                        date = local.toLocalDate()
                        time = local.toLocalTime()
                    }
                    else -> {
                        val local = LocalDateTime.parse(raw.take(19))
                        date = local.toLocalDate()
                        time = local.toLocalTime()
                    }
                }
            }
        }
        return TodoTask(
            id = o.optString("id"),
            content = o.optString("content").trim(),
            parentId = if (o.isNull("parent_id")) null else o.optString("parent_id").takeIf { it.isNotBlank() },
            order = o.optInt("child_order", 0),
            due = date,
            dueTime = time,
            isRecurring = recurring,
            sectionId = if (o.isNull("section_id")) null else o.optString("section_id").takeIf { it.isNotBlank() },
        )
    }

    /** Follows cursor pagination. Also copes with a plain JSON array response. */
    private fun getAll(url: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var cursor: String? = null
        var pages = 0
        do {
            val sep = if (url.contains('?')) "&" else "?"
            val full = url + sep + "limit=100" + (cursor?.let { "&cursor=" + enc(it) } ?: "")
            val body = request("GET", full).trim()
            cursor = null
            if (body.startsWith("[")) {
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
            } else {
                val obj = JSONObject(body)
                val arr = obj.optJSONArray("results") ?: JSONArray()
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
                if (!obj.isNull("next_cursor")) {
                    cursor = obj.optString("next_cursor").takeIf { it.isNotBlank() }
                }
            }
            pages++
        } while (cursor != null && pages < 20)
        return out
    }

    private fun request(method: String, url: String, jsonBody: String? = null): String {
        val (code, text) = httpRequest(method, url, mapOf("Authorization" to "Bearer $token"), jsonBody)
        if (code !in 200..299) {
            val hint = when (code) {
                401, 403 -> "token was rejected"
                404 -> "not found"
                410 -> "endpoint retired"
                429 -> "too many requests"
                else -> text.take(120)
            }
            throw HttpStatusException(code, "Todoist error $code, $hint")
        }
        return text
    }

    private fun enc(s: String): String = urlEncode(s)
}
