package tw.kevinzhang.extension_runtime.session

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent store for per-extension session data captured from WebView.
 * Keyed by extensionId.
 *
 * Persistence: write-through to SharedPreferences ("session_store").
 *   - Cookies stored at key "$extensionId.cookies"
 *   - Tokens stored at key "$extensionId.tokens" (JSON-encoded Map)
 *   - apply() is used for async disk writes to avoid blocking the main thread
 *
 * Thread-safety: writes occur on the main thread (WebView callbacks), reads
 * on Dispatchers.IO (HttpBridge). ConcurrentHashMap with atomic compute()
 * guarantees correctness; SharedPreferences writes use apply() which is
 * thread-safe and asynchronous.
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson,
) {

    private data class SessionData(
        val cookies: String?,
        val tokens: Map<String, String>,
    )

    private val prefs = context.getSharedPreferences("session_store", Context.MODE_PRIVATE)

    // Write-through in-memory cache for synchronous reads (required by HttpBridge on IO thread)
    private val sessions = ConcurrentHashMap<String, SessionData>()

    init {
        // Restore all persisted sessions into the in-memory cache at startup
        val extensionIds = prefs.all.keys
            .mapNotNull { key ->
                when {
                    key.endsWith(".cookies") -> key.removeSuffix(".cookies")
                    key.endsWith(".tokens") -> key.removeSuffix(".tokens")
                    else -> null
                }
            }
            .toSet()

        val tokenType = object : TypeToken<Map<String, String>>() {}.type

        for (extensionId in extensionIds) {
            val cookies = prefs.getString("$extensionId.cookies", null)
            val tokensJson = prefs.getString("$extensionId.tokens", null)
            val tokens: Map<String, String> = tokensJson?.let { json ->
                runCatching { gson.fromJson<Map<String, String>>(json, tokenType) }
                    .getOrElse { emptyMap() }
            } ?: emptyMap()

            if (cookies != null || tokens.isNotEmpty()) {
                sessions[extensionId] = SessionData(cookies, tokens)
            }
        }
    }

    fun putCookies(extensionId: String, cookies: String) {
        sessions.compute(extensionId) { _, existing ->
            (existing ?: SessionData(null, emptyMap())).copy(cookies = cookies)
        }
        persist(extensionId)
    }

    fun putToken(extensionId: String, headerName: String, value: String) {
        sessions.compute(extensionId) { _, existing ->
            val base = existing ?: SessionData(null, emptyMap())
            base.copy(tokens = base.tokens + (headerName to value))
        }
        persist(extensionId)
    }

    fun getCookies(extensionId: String): String? = sessions[extensionId]?.cookies

    fun getTokens(extensionId: String): Map<String, String> =
        sessions[extensionId]?.tokens ?: emptyMap()

    fun hasSession(extensionId: String): Boolean = sessions.containsKey(extensionId)

    fun clearSession(extensionId: String) {
        sessions.remove(extensionId)
        prefs.edit().remove("$extensionId.cookies").remove("$extensionId.tokens").apply()
    }

    fun clearAll() {
        sessions.clear()
        prefs.edit().clear().apply()
    }

    private fun persist(extensionId: String) {
        val data = sessions[extensionId] ?: return
        prefs.edit().apply {
            if (data.cookies != null) putString("$extensionId.cookies", data.cookies)
            else remove("$extensionId.cookies")
            putString("$extensionId.tokens", gson.toJson(data.tokens))
            apply()
        }
    }
}
