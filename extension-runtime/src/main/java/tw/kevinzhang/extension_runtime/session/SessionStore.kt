package tw.kevinzhang.extension_runtime.session

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory store for per-extension session data captured from WebView.
 * Keyed by extensionId. Cleared on app process restart (user must re-login).
 *
 * Thread-safe: writes occur on the main thread (WebView capture) and reads
 * occur on Dispatchers.IO (ExtensionRunner). ConcurrentHashMap with atomic
 * compute() calls guarantees correctness under concurrent access.
 */
@Singleton
class SessionStore @Inject constructor() {

    private data class SessionData(
        val cookies: String?,                      // raw Cookie header value
        val tokens: Map<String, String>,           // extra headers e.g. Authorization
    )

    private val sessions = ConcurrentHashMap<String, SessionData>()

    fun putCookies(extensionId: String, cookies: String) {
        sessions.compute(extensionId) { _, existing ->
            (existing ?: SessionData(null, emptyMap())).copy(cookies = cookies)
        }
    }

    fun putToken(extensionId: String, headerName: String, value: String) {
        sessions.compute(extensionId) { _, existing ->
            val base = existing ?: SessionData(null, emptyMap())
            base.copy(tokens = base.tokens + (headerName to value))
        }
    }

    fun getCookies(extensionId: String): String? = sessions[extensionId]?.cookies

    fun getTokens(extensionId: String): Map<String, String> =
        sessions[extensionId]?.tokens ?: emptyMap()

    fun hasSession(extensionId: String): Boolean = sessions.containsKey(extensionId)

    fun clearSession(extensionId: String) { sessions.remove(extensionId) }
}
