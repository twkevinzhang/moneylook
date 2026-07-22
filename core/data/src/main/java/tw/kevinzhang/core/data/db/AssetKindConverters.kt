package tw.kevinzhang.core.data.db

import androidx.room.TypeConverter
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.AutoCategoryRuleDirection

class AssetKindConverters {
    @TypeConverter
    fun fromAssetKind(value: AssetKind): String = value.name

    @TypeConverter
    fun toAssetKind(value: String): AssetKind = AssetKind.valueOf(value)

    @TypeConverter
    fun fromAssignmentSource(value: AssignmentSource): String = value.name

    @TypeConverter
    fun toAssignmentSource(value: String): AssignmentSource = AssignmentSource.valueOf(value)

    @TypeConverter
    fun fromAutoCategoryRuleDirection(value: AutoCategoryRuleDirection): String = value.name

    @TypeConverter
    fun toAutoCategoryRuleDirection(value: String): AutoCategoryRuleDirection =
        AutoCategoryRuleDirection.valueOf(value)
}
