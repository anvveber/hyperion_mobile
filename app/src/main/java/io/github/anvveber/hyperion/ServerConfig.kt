package io.github.anvveber.hyperion

import android.content.Context
import android.net.Uri
import java.net.URI
import java.net.URISyntaxException

/** The one setting the app has: which server to talk to. */
object ServerConfig {
    private const val PREFS = "server"
    private const val KEY_URL = "url"

    fun get(context: Context): Uri? {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, null)
        return stored?.let { normalize(it) }
    }

    fun set(context: Context, server: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URL, server.toString())
            .apply()
    }

    /**
     * Turns what the user typed into a base URL: scheme defaults to https,
     * default ports, query, fragment and trailing slashes are dropped.
     * Returns null when the input is not a usable http(s) address.
     */
    fun normalize(input: String): Uri? {
        var text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        if (!text.contains("://")) text = "https://$text"

        val parsed = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = parsed.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = parsed.host?.lowercase()
        if (host.isNullOrEmpty() || parsed.rawUserInfo != null) return null

        val port = parsed.port
        val keepPort = port != -1 && port != defaultPort(scheme)
        val authority = if (keepPort) "$host:$port" else host
        val path = (parsed.rawPath ?: "").trimEnd('/')

        return Uri.Builder()
            .scheme(scheme)
            .encodedAuthority(authority)
            .encodedPath(path)
            .build()
    }

    /** "scheme://host[:port]", the form WebView origin rules expect. */
    fun origin(server: Uri): String {
        val port = server.port
        val host = server.host ?: ""
        return if (port == -1) "${server.scheme}://$host" else "${server.scheme}://$host:$port"
    }

    fun isSameOrigin(url: Uri, server: Uri): Boolean {
        val scheme = url.scheme?.lowercase() ?: return false
        if (scheme != server.scheme) return false
        if (!url.host.equals(server.host, ignoreCase = true)) return false
        return effectivePort(url) == effectivePort(server)
    }

    /**
     * True for http(s) hosts under the same parent domain as the server, for
     * example auth.example.org next to eye.example.org. Reverse-proxy login
     * pages usually live there. Uses the last two labels of the server's
     * host, or three under a two-level country suffix such as co.uk, so the
     * parent is never a public suffix shared with unrelated sites.
     */
    fun isSibling(url: Uri, server: Uri): Boolean {
        val scheme = url.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return false
        val host = url.host?.lowercase() ?: return false
        val serverHost = server.host?.lowercase() ?: return false
        val labels = serverHost.split('.')
        if (labels.size < 2 || labels.all { it.all(Char::isDigit) }) return false
        val keep = if (hasTwoLevelSuffix(labels)) 3 else 2
        if (labels.size < keep) return false
        val parent = labels.takeLast(keep).joinToString(".")
        return host == parent || host.endsWith(".$parent")
    }

    /**
     * Second-level labels that country domains sell under (example.co.uk,
     * example.com.au). Not the full public suffix list, which Android does not
     * expose; it covers the common cases, and a miss only means login popups
     * on a sibling host open in the browser instead of the app.
     */
    private val SECOND_LEVEL_LABELS = setOf(
        "ac", "co", "com", "edu", "gov", "gv", "ltd", "mil", "ne", "net", "nom", "or", "org", "plc", "sch",
    )

    private fun hasTwoLevelSuffix(labels: List<String>): Boolean =
        labels.size >= 2 && labels.last().length == 2 && labels[labels.size - 2] in SECOND_LEVEL_LABELS

    /**
     * Resolves an in-app path such as "/people/new" against the server.
     * Anything that would leave the server's origin falls back to the root.
     */
    fun resolve(server: Uri, path: String?): Uri {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) return server
        val candidate = Uri.parse(server.toString() + path)
        return if (isSameOrigin(candidate, server)) candidate else server
    }

    private fun effectivePort(uri: Uri): Int =
        if (uri.port != -1) uri.port else defaultPort(uri.scheme?.lowercase())

    private fun defaultPort(scheme: String?): Int = if (scheme == "http") 80 else 443
}
