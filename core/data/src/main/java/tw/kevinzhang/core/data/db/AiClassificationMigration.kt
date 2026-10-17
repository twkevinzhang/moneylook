package tw.kevinzhang.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import tw.kevinzhang.core.data.model.*

/** A default edited in the legacy editor still carried default flags. Preserve that user work. */
internal fun preserveEditedDefaults(db: SupportSQLiteDatabase) {
    val structured = DefaultClassificationCatalog.publicMccRules + DefaultClassificationCatalog.publicStructuralRules + DefaultClassificationCatalog.publicGenericRules
    val expectedRules = (DefaultClassificationCatalog.publicAutoCategoryRules + structured.map { it.rule }).associateBy { it.id }
    val expectedConditions = structured.associate { it.rule.id to it.conditions }
    val edited = mutableListOf<String>()
    db.query("SELECT * FROM auto_category_rules WHERE origin='PUBLIC_DEFAULT' OR (isDefault=1 AND origin='LEGACY')").use { rows ->
        while(rows.moveToNext()) {
            val id=rows.getString(rows.getColumnIndexOrThrow("id"))
            val expected=expectedRules[id] ?: continue
            val values=mapOf<String,Any?>("name" to expected.name,"descriptionContains" to expected.descriptionContains,
                "direction" to expected.amountSign.name,"minAbsoluteAmount" to expected.minAbsoluteAmount,
                "maxAbsoluteAmount" to expected.maxAbsoluteAmount,"accountId" to expected.accountId,"categoryId" to expected.categoryId,
                "enabled" to if(expected.enabled) 1 else 0,"priority" to expected.priority,
                "descriptionMatchMode" to expected.descriptionMatchMode.name,"ruleSetId" to expected.ruleSetId,
                "extensionId" to expected.extensionId,"accountKind" to expected.accountKind?.name,"action" to expected.action.name)
            val sameRow=values.all { (column,value) ->
                val index=rows.getColumnIndexOrThrow(column)
                when(value) { null -> rows.isNull(index); is Number -> !rows.isNull(index) && rows.getDouble(index)==value.toDouble(); else -> rows.getString(index)==value.toString() }
            }
            val actual=db.query("SELECT position,conditionGroup,field,matchMode,pattern FROM auto_category_rule_conditions WHERE ruleId=? ORDER BY position",arrayOf(id)).use { conditions ->
                buildList { while(conditions.moveToNext()) add(AutoCategoryRuleCondition(id,conditions.getInt(0),
                    AutoCategoryRuleConditionGroup.valueOf(conditions.getString(1)),AutoCategoryRuleConditionField.valueOf(conditions.getString(2)),
                    AutoCategoryRuleConditionMatchMode.valueOf(conditions.getString(3)),conditions.getString(4))) }
            }
            val expectedList=expectedConditions[id].orEmpty().sortedBy { it.position }
            val legacyEquivalent=expectedList.isEmpty() && actual.size==1 && actual.single()==AutoCategoryRuleCondition(
                id,0,AutoCategoryRuleConditionGroup.INCLUDE_ANY,AutoCategoryRuleConditionField.LEGACY_ANY_TEXT,AutoCategoryRuleConditionMatchMode.CONTAINS,
                expected.descriptionContains ?: "__none__")
            val hasTags=db.query("SELECT COUNT(*) FROM auto_category_rule_tag_cross_refs WHERE ruleId=?",arrayOf(id)).use { it.moveToFirst();it.getInt(0)>0 }
            if(!sameRow || (actual!=expectedList && !legacyEquivalent) || hasTags) edited+=id
        }
    }
    edited.forEach { db.execSQL("UPDATE auto_category_rules SET isDefault=0,origin='USER_CONFIRMED',ruleSetId=NULL WHERE id=?",arrayOf(it)) }
}

/** Exact shipped rule/category pairs with no surviving replacement remain source evidence. */
internal fun captureRetiredDefaults(db: SupportSQLiteDatabase) {
    val current=(DefaultClassificationCatalog.publicAutoCategoryRules +
        (DefaultClassificationCatalog.publicMccRules+DefaultClassificationCatalog.publicStructuralRules+DefaultClassificationCatalog.publicGenericRules).map { it.rule })
        .mapNotNull { rule -> rule.categoryId?.let { rule.id to it } }.toMap()
    val retired=current+mapOf("public-rule-003" to "expense-insurance","public-rule-006" to "expense-entertainment",
        "public-rule-010" to "expense-topup","public-rule-011" to "expense-cash","public-rule-015" to "expense-fees",
        "public-rule-017" to "expense-cash","public-rule-019" to "expense-transport",
        "public-structural-auto-deposit-cash-withdrawal-v2" to "expense-cash")
    retired.forEach { (rule,category) ->
        db.execSQL("""INSERT OR IGNORE INTO ai_classification_work
            SELECT transferId,categoryId,autoRuleId,autoRuleSetId,0 FROM transfer_annotations
            WHERE manualOverride=0 AND autoRuleId=? AND categoryId=?
            AND NOT EXISTS(SELECT 1 FROM auto_category_rules WHERE id=?)""",arrayOf(rule,category,rule))
    }
}
