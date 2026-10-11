package io.github.anvveber.hyperion

import android.net.Uri
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Asks the server's public health endpoint whether it is a hyperion server. */
object ServerCheck {
    sealed interface Result {
        /** Answered like hyperion's /api/health. */
        data object Ok : Result

        /** Something answered, but not hyperion (often a reverse-proxy login page). */
        data object Unconfirmed : Result

        data class Unreachable(val reason: String) : Result
    }

    private const val TIMEOUT_MS = 10_000
    private const val MAX_BODY_BYTES = 16 * 1024

    /** Blocking: call from a background thread. */
    fun run(server: Uri): Result {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$server/api/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            if (code !in 200..299) return Result.Unconfirmed
            val body = connection.inputStream.use { readLimited(it) }
            val status = try {
                JSONObject(body).optString("status")
            } catch (e: JSONException) {
                ""
            }
            if (status == "healthy") Result.Ok else Result.Unconfirmed
        } catch (e: IOException) {
            Result.Unreachable(e.localizedMessage ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun readLimited(stream: InputStream): String {
        val buffer = ByteArray(MAX_BODY_BYTES)
        var total = 0
        while (total < buffer.size) {
            val read = stream.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return String(buffer, 0, total, Charsets.UTF_8)
    }
}
