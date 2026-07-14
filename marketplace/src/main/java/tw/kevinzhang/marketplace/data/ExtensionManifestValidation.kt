package tw.kevinzhang.marketplace.data

import java.net.IDN
import java.net.URI
import java.util.Locale
import java.util.TimeZone

private const val MAX_POST_SUBMIT_DELAY_MS = 60_000L

/** Validates untrusted extension metadata and returns a domain-normalized copy. */
fun ExtensionManifest.validateAndNormalize(): ExtensionManifest {
    val normalizedDomains = requireNotNull(targetDomains) { "targetDomains is required" }
        .map { normalizeTargetDomain(requireNotNull(it) { "targetDomains must not contain null" }) }
        .distinct()
    require(normalizedDomains.isNotEmpty()) { "targetDomains must not be empty" }

    val loginUri = try {
        URI(loginUrl)
    } catch (exception: Exception) {
        throw IllegalArgumentException("loginUrl must be a valid HTTPS URL", exception)
    }
    require(loginUri.scheme.equals("https", ignoreCase = true)) {
        "loginUrl must use HTTPS"
    }
    require(loginUri.rawUserInfo == null) { "loginUrl must not contain user info" }
    val loginHost = loginUri.host?.let(::normalizeHost)
        ?: throw IllegalArgumentException("loginUrl must contain a valid host")
    require(normalizedDomains.any { loginHost == it || loginHost.endsWith(".$it") }) {
        "loginUrl host must match targetDomains"
    }

    val automation = requireNotNull(loginAutomation) { "loginAutomation is required" }
    requireSelector("usernameSelector", automation.usernameSelector)
    requireSelector("passwordSelector", automation.passwordSelector)
    requireSelector("captchaImageSelector", automation.captchaImageSelector)
    requireSelector("captchaInputSelector", automation.captchaInputSelector)
    requireSelector("submitSelector", automation.submitSelector)
    require(!automation.successUrlContains.isNullOrBlank()) {
        "loginAutomation.successUrlContains must not be blank"
    }
    automation.postSubmitDelayMs?.let { delayMs ->
        require(delayMs in 0..MAX_POST_SUBMIT_DELAY_MS) {
            "loginAutomation.postSubmitDelayMs must be between 0 and $MAX_POST_SUBMIT_DELAY_MS"
        }
    }

    schedule?.let { scheduleConfig ->
        require(!scheduleConfig.suggestedCron.isNullOrBlank()) {
            "schedule.suggestedCron must not be blank"
        }
        require(scheduleConfig.suggestedTimezone in TimeZone.getAvailableIDs().toSet()) {
            "schedule.suggestedTimezone is invalid: ${scheduleConfig.suggestedTimezone}"
        }
    }

    return copy(targetDomains = normalizedDomains)
}

private fun requireSelector(name: String, selector: String?) {
    require(!selector.isNullOrBlank()) { "loginAutomation.$name must not be blank" }
}

private fun normalizeTargetDomain(domain: String): String {
    val normalized = normalizeHost(domain.trim().trimEnd('.'))
    require(normalized != "localhost" && !normalized.endsWith(".localhost")) {
        "targetDomains must not contain localhost"
    }
    require(!normalized.isIpAddress()) { "targetDomains must not contain IP addresses" }
    return normalized
}

private fun normalizeHost(host: String): String {
    require(host.isNotBlank()) { "targetDomains must not contain blank values" }
    val asciiHost = try {
        IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
    } catch (exception: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid domain: $host", exception)
    }
    return asciiHost.lowercase(Locale.US)
}

private fun String.isIpAddress(): Boolean {
    if (contains(':') || all(Char::isDigit) || startsWith("0x", ignoreCase = true)) return true
    val parts = split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
    }
}
