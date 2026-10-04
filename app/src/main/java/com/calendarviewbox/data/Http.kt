package com.calendarviewbox.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The server answered, but with an error status. Not a connection problem. */
class HttpStatusException(val code: Int, message: String) : IOException(message)

/** True when the failure looks like no internet (DNS, timeout, refused), not a server error. */
fun isNetworkProblem(e: Throwable): Boolean = e is IOException && e !is HttpStatusException

fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

/** Small blocking HTTP helper. Call it from Dispatchers.IO. */
fun httpRequest(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    jsonBody: String? = null,
): Pair<Int, String> {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = 15_000
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    try {
        if (method == "POST") {
            val bytes = (jsonBody ?: "").toByteArray(Charsets.UTF_8)
            conn.doOutput = true
            if (jsonBody != null) conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return code to text
    } finally {
        conn.disconnect()
    }
}
