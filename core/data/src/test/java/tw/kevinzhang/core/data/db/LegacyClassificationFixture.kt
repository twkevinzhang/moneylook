package tw.kevinzhang.core.data.db

/** Historical migrations must start from the pre-AI bundled catalog, not today's fresh install. */
internal fun seedLegacyRules(database: MoneylookDatabase): MoneylookDatabase {
    val sql = database.openHelper.writableDatabase
    DefaultClassificationSeeder.seedRulesForExistingCategories(sql)
    DefaultClassificationSeeder.seedRulesV2ForExistingCategories(sql)
    DefaultClassificationSeeder.seedRulesV3ForExistingCategories(sql)
    return database
}
