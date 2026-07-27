package tw.kevinzhang.core.data.db

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.time.ZoneId
import tw.kevinzhang.core.data.model.CredentialProfile

data class CredentialProfileCsvFieldDefinition(
    val key: String,
    val label: String,
    val type: String,
    val required: Boolean,
)

/**
 * One extension profile plus the manifest field definitions needed to interpret its flat JSON
 * credential. Password values remain plaintext in [profile], exactly as required by the backup
 * contract.
 */
data class CredentialProfileCsvExtension(
    val extensionId: String,
    val extensionName: String,
    val profile: CredentialProfile,
    val fields: List<CredentialProfileCsvFieldDefinition>,
)

sealed interface CredentialProfileCsvDecodeResult {
    data class Success(
        val value: List<CredentialProfileCsvExtension>,
    ) : CredentialProfileCsvDecodeResult

    data class Failure(val reason: String) : CredentialProfileCsvDecodeResult
}

/**
 * Strict, all-or-nothing codec for the plaintext credential backup.
 *
 * A row represents one extension-defined credential field. Extension and schedule metadata are
 * repeated on every row and must be identical, which lets the decoder reject spliced or partial
 * profiles before a caller starts a Room transaction.
 */
object CredentialProfileCsvCodec {
    private const val MARKER = "moneylook-credential-profiles"
    private const val VERSION = "1"
    private const val MAX_CSV_CHARS = 1_000_000
    private const val MAX_ROWS = 10_000
    private const val MAX_CELL_CHARS = 16_384
    private const val MAX_EXTENSIONS = 2_000
    private const val MAX_FIELDS_PER_EXTENSION = 16
    private const val MAX_EXTENSION_ID_LENGTH = 512
    private const val MAX_EXTENSION_NAME_LENGTH = 200
    private const val MAX_FIELD_LABEL_LENGTH = 80
    private const val MAX_CRON_LENGTH = 256
    private const val MAX_TIMEZONE_LENGTH = 100
    private val FIELD_KEY = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val FIELD_TYPES = setOf("text", "password")
    private val gson = Gson()

    fun encode(extensions: List<CredentialProfileCsvExtension>): String {
        require(extensions.isNotEmpty()) { "no credential profiles" }
        require(extensions.size <= MAX_EXTENSIONS) { "too many credential profiles" }
        require(extensions.map { it.extensionId }.distinct().size == extensions.size) {
            "duplicate extension id"
        }

        val rows = mutableListOf<List<String>>()
        rows += listOf(MARKER, VERSION)
        rows += HEADER
        extensions.sortedBy { it.extensionId }.forEach { extension ->
            validateExtensionMetadata(extension)
            val values = parseCredential(extension.profile.credential)
            val keys = extension.fields.map { it.key }
            require(values.keys == keys.toSet()) { "credential fields do not match ${extension.extensionId}" }
            extension.fields.forEach { field ->
                validateField(field)
                val value = values.getValue(field.key)
                validateRequiredValue(field, value)
                rows += listOf(
                    extension.extensionId,
                    extension.extensionName,
                    field.key,
                    field.label,
                    field.type,
                    field.required.toString(),
                    value,
                    extension.profile.scheduleEnabled.toString(),
                    extension.profile.scheduleCron,
                    extension.profile.timezoneId,
                )
            }
        }
        require(rows.size <= MAX_ROWS) { "too many rows" }
        return StrictCsv.encode(rows)
    }

    fun decode(csv: String): CredentialProfileCsvDecodeResult = try {
        val rows = StrictCsv.parse(csv, MAX_CSV_CHARS, MAX_ROWS, MAX_CELL_CHARS)
        require(rows.size >= 3) { "missing credential rows" }
        require(rows[0] == listOf(MARKER, VERSION)) { "missing marker or unsupported version" }
        require(rows[1] == HEADER) { "unexpected credential header" }

        data class Builder(
            val extensionId: String,
            val extensionName: String,
            val scheduleEnabled: Boolean,
            val scheduleCron: String,
            val timezoneId: String,
            val fields: MutableList<CredentialProfileCsvFieldDefinition> = mutableListOf(),
            val values: LinkedHashMap<String, String> = linkedMapOf(),
        )

        val builders = linkedMapOf<String, Builder>()
        rows.drop(2).forEachIndexed { index, row ->
            require(row.size == HEADER.size) {
                "row ${index + 3} has an unexpected column count"
            }
            val extensionId = boundedRequired(row[0], "extensionId", MAX_EXTENSION_ID_LENGTH)
            val extensionName = boundedRequired(
                row[1],
                "extensionName",
                MAX_EXTENSION_NAME_LENGTH,
            )
            val field = CredentialProfileCsvFieldDefinition(
                key = row[2],
                label = row[3],
                type = row[4],
                required = bool(row[5]),
            )
            validateField(field)
            val value = row[6]
            validateRequiredValue(field, value)
            val scheduleEnabled = bool(row[7])
            val scheduleCron = row[8]
            val timezoneId = row[9]
            validateSchedule(scheduleEnabled, scheduleCron, timezoneId)

            val builder = builders.getOrPut(extensionId) {
                Builder(
                    extensionId = extensionId,
                    extensionName = extensionName,
                    scheduleEnabled = scheduleEnabled,
                    scheduleCron = scheduleCron,
                    timezoneId = timezoneId,
                )
            }
            require(
                builder.extensionName == extensionName &&
                    builder.scheduleEnabled == scheduleEnabled &&
                    builder.scheduleCron == scheduleCron &&
                    builder.timezoneId == timezoneId,
            ) { "inconsistent metadata for $extensionId" }
            require(builder.values.putIfAbsent(field.key, value) == null) {
                "duplicate field ${field.key} for $extensionId"
            }
            builder.fields += field
            require(builder.fields.size <= MAX_FIELDS_PER_EXTENSION) {
                "too many fields for $extensionId"
            }
        }

        require(builders.isNotEmpty()) { "no credential profiles" }
        require(builders.size <= MAX_EXTENSIONS) { "too many credential profiles" }
        CredentialProfileCsvDecodeResult.Success(
            builders.values.map { builder ->
                CredentialProfileCsvExtension(
                    extensionId = builder.extensionId,
                    extensionName = builder.extensionName,
                    profile = CredentialProfile(
                        extensionId = builder.extensionId,
                        credential = gson.toJson(builder.values),
                        scheduleEnabled = builder.scheduleEnabled,
                        scheduleCron = builder.scheduleCron,
                        timezoneId = builder.timezoneId,
                    ),
                    fields = builder.fields.toList(),
                )
            },
        )
    } catch (error: IllegalArgumentException) {
        CredentialProfileCsvDecodeResult.Failure(error.message ?: "invalid credential CSV")
    }

    private fun validateExtensionMetadata(extension: CredentialProfileCsvExtension) {
        require(extension.profile.extensionId == extension.extensionId) {
            "profile extension id does not match"
        }
        boundedRequired(extension.extensionId, "extensionId", MAX_EXTENSION_ID_LENGTH)
        boundedRequired(extension.extensionName, "extensionName", MAX_EXTENSION_NAME_LENGTH)
        require(extension.fields.isNotEmpty()) { "credential profile has no fields" }
        require(extension.fields.size <= MAX_FIELDS_PER_EXTENSION) {
            "too many fields for ${extension.extensionId}"
        }
        require(extension.fields.map { it.key }.distinct().size == extension.fields.size) {
            "duplicate credential field"
        }
        validateSchedule(
            extension.profile.scheduleEnabled,
            extension.profile.scheduleCron,
            extension.profile.timezoneId,
        )
    }

    private fun validateField(field: CredentialProfileCsvFieldDefinition) {
        require(FIELD_KEY.matches(field.key)) { "invalid credential field key" }
        boundedRequired(field.label, "fieldLabel", MAX_FIELD_LABEL_LENGTH)
        require(field.type in FIELD_TYPES) { "invalid credential field type" }
    }

    private fun validateRequiredValue(field: CredentialProfileCsvFieldDefinition, value: String) {
        if (field.required) {
            if (field.type == "password") {
                require(value.isNotEmpty()) { "required credential value is empty" }
            } else {
                require(value.isNotBlank()) { "required credential value is blank" }
            }
        }
    }

    private fun validateSchedule(enabled: Boolean, cron: String, timezoneId: String) {
        require(cron == cron.trim()) { "scheduleCron must be trimmed" }
        require(cron.length <= MAX_CRON_LENGTH) { "scheduleCron is too long" }
        require(timezoneId == timezoneId.trim()) { "timezoneId must be trimmed" }
        require(timezoneId.length <= MAX_TIMEZONE_LENGTH) { "timezoneId is too long" }
        if (!enabled) return
        boundedRequired(cron, "scheduleCron", MAX_CRON_LENGTH)
        require(cron.split(Regex("\\s+")).size == 5) { "scheduleCron must have five fields" }
        boundedRequired(timezoneId, "timezoneId", MAX_TIMEZONE_LENGTH)
        require(runCatching { ZoneId.of(timezoneId) }.isSuccess) { "invalid timezoneId" }
    }

    private fun parseCredential(json: String): Map<String, String> {
        val element = runCatching { JsonParser.parseString(json) }.getOrNull()
            ?: throw IllegalArgumentException("invalid credential JSON")
        require(element.isJsonObject) { "credential must be an object" }
        return buildMap {
            element.asJsonObject.entrySet().forEach { (key, value) ->
                require(value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    "credential values must be strings"
                }
                put(key, value.asString)
            }
        }
    }

    private fun boundedRequired(value: String, name: String, maxLength: Int): String {
        require(value.isNotBlank()) { "missing $name" }
        require(value == value.trim()) { "$name must be trimmed" }
        require(value.length <= maxLength) { "$name is too long" }
        return value
    }

    private fun bool(value: String): Boolean = when (value) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("invalid boolean")
    }

    private val HEADER = listOf(
        "extensionId",
        "extensionName",
        "fieldKey",
        "fieldLabel",
        "fieldType",
        "fieldRequired",
        "value",
        "scheduleEnabled",
        "scheduleCron",
        "timezoneId",
    )
}
