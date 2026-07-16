package tw.kevinzhang.moneylook.ui.home

import com.google.gson.Gson
import com.google.gson.JsonParser

data class CredentialFieldDefinition(
    val key: String,
    val label: String,
    val type: String,
    val required: Boolean,
    val summary: Boolean,
) {
    val isPassword: Boolean get() = type == TYPE_PASSWORD

    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_PASSWORD = "password"
    }
}

internal val LEGACY_CREDENTIAL_FIELD_DEFINITIONS = listOf(
    CredentialFieldDefinition("username", "網銀帳號", "text", required = true, summary = true),
    CredentialFieldDefinition("password", "網銀密碼", "password", required = true, summary = false),
)

internal data class ResolvedCredentialValues(
    val values: Map<String, String>,
    val missingRequiredField: CredentialFieldDefinition?,
)

internal class CredentialJsonCodec(private val gson: Gson) {
    fun parseFields(json: String): List<CredentialFieldDefinition> =
        parseFieldsOrNull(json) ?: LEGACY_CREDENTIAL_FIELD_DEFINITIONS

    fun parseCredential(json: String): Map<String, String>? {
        return try {
            val element = JsonParser.parseString(json)
            if (!element.isJsonObject) return null
            buildMap {
                element.asJsonObject.entrySet().forEach { (key, value) ->
                    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
                    put(key, value.asString)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun encodeCredential(values: Map<String, String>): String = gson.toJson(values)

    fun resolveForSave(
        fields: List<CredentialFieldDefinition>,
        submittedValues: Map<String, String>,
        existingValues: Map<String, String>,
    ): ResolvedCredentialValues {
        val resolvedValues = fields.associate { field ->
            val submitted = submittedValues[field.key].orEmpty()
            val resolved = when {
                field.isPassword && submitted.isEmpty() -> existingValues[field.key].orEmpty()
                field.isPassword -> submitted
                else -> submitted.trim()
            }
            field.key to resolved
        }
        val missingRequiredField = fields.firstOrNull { field ->
            if (!field.required) return@firstOrNull false
            val value = resolvedValues[field.key].orEmpty()
            if (field.isPassword) value.isEmpty() else value.isBlank()
        }
        return ResolvedCredentialValues(resolvedValues, missingRequiredField)
    }

    private fun parseFieldsOrNull(json: String): List<CredentialFieldDefinition>? {
        return try {
            val element = JsonParser.parseString(json)
            if (!element.isJsonArray || element.asJsonArray.size() !in 1..MAX_FIELDS) return null
            val keys = mutableSetOf<String>()
            element.asJsonArray.map { fieldElement ->
                if (!fieldElement.isJsonObject) return null
                val field = fieldElement.asJsonObject
                val key = field.string("key") ?: return null
                val label = field.string("label") ?: return null
                val type = field.string("type") ?: return null
                val required = field.boolean("required") ?: return null
                val summary = field.boolean("summary") ?: return null
                if (!FIELD_KEY.matches(key) || label.isBlank() || label.length > MAX_LABEL_LENGTH) return null
                if (type != CredentialFieldDefinition.TYPE_TEXT && type != CredentialFieldDefinition.TYPE_PASSWORD) {
                    return null
                }
                if (!keys.add(key) || (type == CredentialFieldDefinition.TYPE_PASSWORD && summary)) return null
                CredentialFieldDefinition(key, label, type, required, summary)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun com.google.gson.JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun com.google.gson.JsonObject.boolean(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private companion object {
        const val MAX_FIELDS = 16
        const val MAX_LABEL_LENGTH = 80
        val FIELD_KEY = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    }
}
