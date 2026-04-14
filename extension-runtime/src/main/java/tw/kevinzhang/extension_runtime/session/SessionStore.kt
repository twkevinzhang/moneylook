package tw.kevinzhang.extension_runtime.session

import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory store for per-extension session data captured from WebView.
 * Keyed by extensionId. Cleared on app process restart (user must re-login).
 */
@Singleton
class SessionStore @Inject constructor() {

    private data class SessionData(
        val cookies: String?,                      // raw Cookie header value
        val tokens: Map<String, String>,           // extra headers e.g. Authorization
    )

    private val sessions = mutableMapOf<String, SessionData>()

    fun putCookies(extensionId: String, cookies: String) {
        val existing = sessions[extensionId] ?: SessionData(null, emptyMap())
        sessions[extensionId] = existing.copy(cookies = cookies)
    }

    fun putToken(extensionId: String, headerName: String, value: String) {
        val existing = sessions[extensionId] ?: SessionData(null, emptyMap())
        sessions[extensionId] = existing.copy(tokens = existing.tokens + (headerName to value))
    }

    fun getCookies(extensionId: String): String? = sessions[extensionId]?.cookies

    fun getTokens(extensionId: String): Map<String, String> =
        sessions[extensionId]?.tokens ?: emptyMap()

    fun hasSession(extensionId: String): Boolean = sessions.containsKey(extensionId)

    fun clearSession(extensionId: String) { sessions.remove(extensionId) }
}
