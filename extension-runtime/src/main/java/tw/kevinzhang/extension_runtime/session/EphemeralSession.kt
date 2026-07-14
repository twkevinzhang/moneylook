package tw.kevinzhang.extension_runtime.session

import java.util.Locale

/**
 * Immutable, in-memory-only cookie snapshot created by one native login run.
 *
 * Cookie values are deliberately not exposed through the JavaScript SDK. The HTTP bridge
 * selects a cookie header by request host and injects it after validating the request.
 */
class EphemeralSession private constructor(
    cookieHeadersByDomain: Map<String, String>,
) {
    private val cookieHeadersByDomain = cookieHeadersByDomain
        .mapKeys { (domain, _) -> normalizeDomain(domain) }
        .filterKeys(String::isNotEmpty)
        .filterValues(String::isNotBlank)
        .toMap()

    val isEmpty: Boolean
        get() = cookieHeadersByDomain.isEmpty()

    internal fun cookieHeaderFor(host: String): String? {
        val normalizedHost = host.lowercase(Locale.US).trimEnd('.')
        return cookieHeadersByDomain.entries
            .filter { (domain, _) ->
                normalizedHost == domain || normalizedHost.endsWith(".$domain")
            }
            .maxByOrNull { (domain, _) -> domain.length }
            ?.value
    }

    companion object {
        val Empty = EphemeralSession(emptyMap())

        fun of(cookieHeadersByDomain: Map<String, String>): EphemeralSession =
            EphemeralSession(cookieHeadersByDomain)

        private fun normalizeDomain(domain: String): String = domain
            .trim()
            .removePrefix(".")
            .lowercase(Locale.US)
            .trimEnd('.')
    }
}
