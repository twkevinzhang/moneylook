package tw.kevinzhang.marketplace.data

import java.net.URI
import java.util.TimeZone

/** Validates untrusted extension metadata before it is stored or used to construct a download URL. */
fun ExtensionManifest.validateAndNormalize(): ExtensionManifest {
    require(id.isNotBlank()) { "id must not be blank" }
    require(name.isNotBlank()) { "name must not be blank" }
    require(version > 0) { "version must be greater than zero" }
    require(versionName.isNotBlank()) { "versionName must not be blank" }

    val credentialConfig = requireNotNull(credential) { "credential is required" }
    val credentialFields = requireNotNull(credentialConfig.fields) { "credential.fields is required" }
    require(credentialFields.isNotEmpty()) { "credential.fields must not be empty" }
    require(credentialFields.size <= MAX_CREDENTIAL_FIELDS) {
        "credential.fields must contain at most $MAX_CREDENTIAL_FIELDS fields"
    }
    val credentialKeys = mutableSetOf<String>()
    credentialFields.forEachIndexed { index, rawField ->
        val field = requireNotNull(rawField) { "credential.fields[$index] is required" }
        val key = requireNotNull(field.key) { "credential.fields[$index].key is required" }
        val label = requireNotNull(field.label) { "credential.fields[$index].label is required" }
        val type = requireNotNull(field.type) { "credential.fields[$index].type is required" }
        requireNotNull(field.required) { "credential.fields[$index].required is required" }
        val summary = requireNotNull(field.summary) { "credential.fields[$index].summary is required" }
        require(CREDENTIAL_KEY.matches(key)) {
            "credential.fields[$index].key is invalid: $key"
        }
        require(credentialKeys.add(key)) {
            "credential.fields contains duplicate key: $key"
        }
        require(label.isNotBlank()) { "credential.fields[$index].label must not be blank" }
        require(label.length <= MAX_CREDENTIAL_LABEL_LENGTH) {
            "credential.fields[$index].label must contain at most $MAX_CREDENTIAL_LABEL_LENGTH characters"
        }
        require(type in CREDENTIAL_FIELD_TYPES) {
            "credential.fields[$index].type must be text or password"
        }
        require(type != "password" || !summary) {
            "credential.fields[$index] cannot expose a password as a summary"
        }
    }

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

private const val MAX_CREDENTIAL_FIELDS = 16
private const val MAX_CREDENTIAL_LABEL_LENGTH = 80
private val CREDENTIAL_KEY = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
private val CREDENTIAL_FIELD_TYPES = setOf("text", "password")

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
