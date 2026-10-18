package io.github.anvveber.hyperion

import android.content.Context
import android.os.Build
import android.webkit.WebView
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash on the phone so it can be read and copied on the next
 * launch. Nothing is sent anywhere: there is no crash reporting service on
 * GrapheneOS without Play, and the app should not phone home anyway.
 *
 * Covers crashes in the app's own process (Kotlin/Java exceptions, including
 * those thrown inside WebView callbacks) and crashes of the WebView renderer.
 * A native crash inside WebView itself kills the process without running
 * Java code, so it only shows up in logcat.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val MAX_CHARS = 32 * 1024

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                write(app, "Uncaught exception on thread \"${thread.name}\"\n\n$trace")
            } catch (ignored: Throwable) {
                // Writing the log must never hide the original crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun write(context: Context, body: String) {
        val header = buildString {
            append("hyperion ").append(BuildConfig.VERSION_NAME).append('\n')
            append("Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("WebView ").append(webViewVersion()).append('\n')
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())).append("\n\n")
        }
        File(context.filesDir, FILE).writeText((header + body).take(MAX_CHARS))
    }

    /** The saved crash, removed once read. Null when there is none. */
    fun take(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (e: IOException) {
            null
        }
        file.delete()
        return text?.takeIf { it.isNotBlank() }
    }

    private fun webViewVersion(): String = try {
        WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "unknown"
    } catch (ignored: Throwable) {
        "unknown"
    }
}
