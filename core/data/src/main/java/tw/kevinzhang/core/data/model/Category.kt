package tw.kevinzhang.core.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A user-managed category shared by every installed extension. */
@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class Category(
    @PrimaryKey val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val color: String,
) {
    init {
        require(name == name.trim() && name.isNotEmpty()) { "category name must be non-blank and trimmed" }
        require(color.isNotBlank()) { "category color must not be blank" }
    }
}
