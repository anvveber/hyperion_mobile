package io.github.anvveber.hyperion

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.URLUtil
import java.io.IOException

/** Saves files the webapp offers for download into the public Downloads folder. */
object Downloads {
    /**
     * Largest file the page may hand over (bridge.js checks this before
     * reading the file). Base64 makes it a third bigger, and the text exists
     * several times over while being parsed and decoded, so this stays well
     * under the app's memory limit. Larger exports: use the browser.
     */
    const val MAX_FILE_BYTES = 18 * 1024 * 1024

    /** Base64 text above this size is refused rather than decoded. */
    const val MAX_BASE64_CHARS = MAX_FILE_BYTES / 3 * 4 + 4

    /** A whole bridge message: the base64 text plus a little JSON around it. */
    const val MAX_MESSAGE_CHARS = MAX_BASE64_CHARS + 4096

    /**
     * Server-side downloads (audio recordings, calendar files). DownloadManager
     * makes its own request, so it needs the session cookie and user agent.
     * Returns the file name it will be saved under.
     */
    fun enqueue(
        context: Context,
        url: String,
        userAgent: String,
        contentDisposition: String?,
        mimeType: String?,
    ): String {
        val name = cleanName(URLUtil.guessFileName(url, contentDisposition, mimeType))
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            addRequestHeader("User-Agent", userAgent)
            if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
            setTitle(name)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        }
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: throw IllegalStateException("DownloadManager is not available")
        manager.enqueue(request)
        return name
    }

    /**
     * Files the page built itself (exports, vCards, backups), handed over as
     * base64 by bridge.js. Blocking: call from a background thread.
     */
    fun saveBase64(context: Context, rawName: String, mimeType: String, base64: String): String? {
        if (base64.length > MAX_BASE64_CHARS) return null
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
        val name = cleanName(rawName)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("No output stream")
            stream.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            name
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            null
        } catch (e: SecurityException) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Keeps a file name from the page to something safe for a single path segment. */
    private fun cleanName(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .trimStart('.')
            .take(120)
        return cleaned.ifEmpty { "download" }
    }
}
