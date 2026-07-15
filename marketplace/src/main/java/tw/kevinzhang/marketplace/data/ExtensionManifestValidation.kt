package tw.kevinzhang.marketplace.data

import java.net.URI
import java.util.TimeZone

/** Validates untrusted extension metadata before it is stored or used to construct a download URL. */
fun ExtensionManifest.validateAndNormalize(): ExtensionManifest {
    require(id.isNotBlank()) { "id must not be blank" }
    require(name.isNotBlank()) { "name must not be blank" }
    require(version > 0) { "version must be greater than zero" }
    require(versionName.isNotBlank()) { "versionName must not be blank" }

    val scriptPath = requireNotNull(syncTrigger) { "syncTrigger is required" }.scriptPath
    requireSafeScriptPath(scriptPath)

    schedule?.let { scheduleConfig ->
        require(!scheduleConfig.suggestedCron.isNullOrBlank()) {
            "schedule.suggestedCron must not be blank"
        }
        require(scheduleConfig.suggestedTimezone in TimeZone.getAvailableIDs().toSet()) {
            "schedule.suggestedTimezone is invalid: ${scheduleConfig.suggestedTimezone}"
        }
    }

    return this
}

private fun requireSafeScriptPath(scriptPath: String) {
    require(scriptPath.isNotBlank()) { "syncTrigger.scriptPath must not be blank" }
    require('\\' !in scriptPath) { "syncTrigger.scriptPath must use URL path separators" }
    val uri = try {
        URI(scriptPath)
    } catch (exception: Exception) {
        throw IllegalArgumentException("syncTrigger.scriptPath is invalid", exception)
    }
    require(!uri.isAbsolute && uri.rawAuthority == null && uri.rawQuery == null && uri.rawFragment == null) {
        "syncTrigger.scriptPath must be a relative path without query or fragment"
    }
    require(!scriptPath.startsWith('/')) { "syncTrigger.scriptPath must be relative" }
    require(scriptPath.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "syncTrigger.scriptPath contains an unsafe path segment"
    }
}
