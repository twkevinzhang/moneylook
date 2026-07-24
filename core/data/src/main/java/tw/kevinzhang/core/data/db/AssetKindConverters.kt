package tw.kevinzhang.core.data.db

import androidx.room.TypeConverter
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.AutoCategoryRuleDirection
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.CategoryKind
import tw.kevinzhang.core.data.model.AutoCategoryRuleAction
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionField
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionGroup
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin

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

    @TypeConverter
    fun fromAutoCategoryRuleDescriptionMatchMode(value: AutoCategoryRuleDescriptionMatchMode): String = value.name

    @TypeConverter
    fun toAutoCategoryRuleDescriptionMatchMode(value: String): AutoCategoryRuleDescriptionMatchMode =
        AutoCategoryRuleDescriptionMatchMode.valueOf(value)

    @TypeConverter
    fun fromCategoryKind(value: CategoryKind): String = value.name

    @TypeConverter
    fun toCategoryKind(value: String): CategoryKind = CategoryKind.valueOf(value)

    @TypeConverter
    fun fromRuleOrigin(value: AutoCategoryRuleOrigin): String = value.name

    @TypeConverter
    fun toRuleOrigin(value: String): AutoCategoryRuleOrigin = AutoCategoryRuleOrigin.valueOf(value)

    @TypeConverter
    fun fromRuleAction(value: AutoCategoryRuleAction): String = value.name

    @TypeConverter
    fun toRuleAction(value: String): AutoCategoryRuleAction = AutoCategoryRuleAction.valueOf(value)

    @TypeConverter
    fun fromRuleConditionField(value: AutoCategoryRuleConditionField): String = value.name

    @TypeConverter
    fun toRuleConditionField(value: String): AutoCategoryRuleConditionField =
        AutoCategoryRuleConditionField.valueOf(value)

    @TypeConverter
    fun fromRuleConditionMatchMode(value: AutoCategoryRuleConditionMatchMode): String = value.name

    @TypeConverter
    fun toRuleConditionMatchMode(value: String): AutoCategoryRuleConditionMatchMode =
        AutoCategoryRuleConditionMatchMode.valueOf(value)

    @TypeConverter
    fun fromRuleConditionGroupOperator(value: AutoCategoryRuleConditionGroup): String = value.name

    @TypeConverter
    fun toRuleConditionGroupOperator(value: String): AutoCategoryRuleConditionGroup =
        AutoCategoryRuleConditionGroup.valueOf(value)
}
