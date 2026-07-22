package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AssignmentSource {
    AUTO,
    MANUAL,
}

/**
 * User-owned metadata deliberately has no foreign key to [Transfer]. A history range replacement
 * deletes and re-inserts bank transfers, and must not erase a user's category, tags, or note.
 */
@Entity(
    tableName = "transfer_annotations",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["extensionId"]), Index(value = ["categoryId"])],
)
data class TransferAnnotation(
    @PrimaryKey val transferId: String,
    /** Stored so metadata can be explicitly removed if an extension is uninstalled. */
    val extensionId: String,
    val categoryId: String? = null,
    val note: String = "",
    val categoryAssignment: AssignmentSource = AssignmentSource.AUTO,
    /** True also represents an intentional manual clear (categoryId == null). */
    val manualOverride: Boolean = categoryAssignment == AssignmentSource.MANUAL,
) {
    init {
        require(manualOverride == (categoryAssignment == AssignmentSource.MANUAL)) {
            "manualOverride must match categoryAssignment"
        }
    }
}

@Entity(
    tableName = "transfer_tag_cross_refs",
    primaryKeys = ["transferId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tagId"])],
)
data class TransferTagCrossRef(
    val transferId: String,
    val tagId: String,
    val source: AssignmentSource = AssignmentSource.MANUAL,
)
