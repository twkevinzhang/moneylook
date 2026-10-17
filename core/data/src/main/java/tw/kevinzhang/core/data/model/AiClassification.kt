package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

sealed interface AiCategorySelection {
    data class Existing(val categoryId: String) : AiCategorySelection
    data class New(val name: String, val reportingGroup: CategoryReportingGroup) : AiCategorySelection
}
data class AiCategoryProposal(val selection: AiCategorySelection, val reason: String, val provider: String? = null, val model: String? = null)

@Entity(tableName = "ai_classification_state")
data class AiClassificationState(@PrimaryKey val id: Int = 1, val started: Boolean = false, val complete: Boolean = false)

/** Source snapshot survives removal of bundled rules. It never contains bank credentials. */
@Entity(tableName = "ai_classification_work")
data class AiClassificationWork(
    @PrimaryKey val transferId: String,
    val categoryId: String?,
    val ruleId: String?,
    val ruleSetId: String?,
    val completed: Boolean = false,
)
