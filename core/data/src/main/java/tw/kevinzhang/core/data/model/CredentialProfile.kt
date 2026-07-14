package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.ForeignKey

@Entity(
    tableName = "credential_profiles",
    primaryKeys = ["extensionId"],
    foreignKeys = [
        ForeignKey(
            entity = InstalledExtension::class,
            parentColumns = ["id"],
            childColumns = ["extensionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CredentialProfile(
    val extensionId: String,
    val username: String,
    val password: String,
    /** Permission snapshot explicitly accepted when the user saves credentials. */
    val approvedLoginHost: String,
    val approvedDomainsJson: String,
    val scheduleEnabled: Boolean = true,
    val scheduleCron: String,
    val timezoneId: String,
    val lastRunAt: Long? = null,
    val lastRunStatus: String? = null,
) {
    override fun toString(): String =
        "CredentialProfile(extensionId=$extensionId, credentials=[REDACTED], " +
            "scheduleEnabled=$scheduleEnabled, scheduleCron=$scheduleCron, " +
            "timezoneId=$timezoneId, lastRunAt=$lastRunAt, lastRunStatus=$lastRunStatus)"
}
