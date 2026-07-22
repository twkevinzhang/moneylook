package tw.kevinzhang.core.data.db

import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleDirection
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CategoryKind

/**
 * Stable, public classification data bundled with the app.
 *
 * Category IDs and rule IDs are database identities: never derive them from user data or rename
 * an existing value. New public rules are appended to [publicAutoCategoryRules].
 */
object DefaultClassificationCatalog {
    val categories: List<Category> = listOf(
        category("expense-food", "餐飲", "🍽️", "#FB8C00", CategoryKind.EXPENSE),
        category("expense-clothing", "服飾", "👕", "#F9C928", CategoryKind.EXPENSE),
        category("expense-home", "住家", "🏠", "#9CD948", CategoryKind.EXPENSE),
        category("expense-transport", "交通", "🚌", "#3E8EEA", CategoryKind.EXPENSE),
        category("expense-learning", "學習", "📘", "#4169E1", CategoryKind.EXPENSE),
        category("expense-entertainment", "休閒娛樂", "🎮", "#8739E8", CategoryKind.EXPENSE),
        category("expense-shopping", "購物", "🛒", "#45C7E8", CategoryKind.EXPENSE),
        category("expense-medical", "醫療", "🩺", "#EF5350", CategoryKind.EXPENSE),
        category("expense-cash", "現金消費", "💵", "#43B96D", CategoryKind.EXPENSE),
        category("expense-insurance", "保險", "🛡️", "#EC80BD", CategoryKind.EXPENSE),
        category("expense-fees", "費用/手續費", "💸", "#66C94D", CategoryKind.EXPENSE),
        category("expense-tax", "稅金", "🧾", "#109C91", CategoryKind.EXPENSE),
        category("expense-gift", "禮物", "🎁", "#EF5350", CategoryKind.EXPENSE),
        category("expense-business", "合夥生意", "🍻", "#EF5350", CategoryKind.EXPENSE),
        category("expense-phone", "電信費", "📞", "#3F63D8", CategoryKind.EXPENSE),
        category("expense-internet", "網路活動", "🖥️", "#8439E9", CategoryKind.EXPENSE),
        category("expense-topup", "儲值", "🍴", "#4169E1", CategoryKind.EXPENSE),
        category("expense-ipass", "iPASS 儲值", "🎫", "#72C95B", CategoryKind.EXPENSE),
        category("expense-jkopay", "街口儲值", "🎟️", "#EF5350", CategoryKind.EXPENSE),
        category("expense-dispute", "爭議", "🐾", "#EF5350", CategoryKind.EXPENSE),
        category("income-salary", "薪資", "💰", "#43B96D", CategoryKind.INCOME),
        category("income-bonus", "獎金", "✨", "#F9C928", CategoryKind.INCOME),
        category("income-refund", "退款", "↩️", "#3E8EEA", CategoryKind.INCOME),
        category("transfer-account", "帳戶移轉", "🔄", "#607D8B", CategoryKind.TRANSFER),
    )

    /**
     * Reviewed, broadly reusable rules only. These IDs deliberately remain stable across releases,
     * and every description is a reviewed generic fragment without private transaction context.
     */
    val publicAutoCategoryRules: List<AutoCategoryRule> = listOf(
        rule("public-rule-001", "儲值｜LINE Pay Money", "line pay money", "expense-topup", 10),
        rule("public-rule-002", "餐飲｜foodpanda", "foodpanda", "expense-food", 20),
        rule("public-rule-003", "保險｜保險公司", "保險股份有限公司", "expense-insurance", 30),
        rule("public-rule-004", "交通｜YouBike", "youbike", "expense-transport", 40),
        rule("public-rule-005", "網路活動｜GitHub", "github", "expense-internet", 50),
        rule("public-rule-006", "休閒娛樂｜Steam", "steam", "expense-entertainment", 60),
        rule("public-rule-010", "儲值｜自動加值", "自動加值", "expense-topup", 100),
        rule("public-rule-011", "現金消費｜跨行提款", "跨行提款", "expense-cash", 110),
        rule("public-rule-012", "現金消費｜卡片提款", "卡片提款", "expense-cash", 120),
        rule("public-rule-013", "交通｜加油站", "加油站", "expense-transport", 130),
        rule("public-rule-014", "交通｜停車服務", "日月亭", "expense-transport", 140),
        rule("public-rule-015", "費用／手續費｜國外交易", "國外交易手續費", "expense-fees", 150),
        rule("public-rule-016", "費用／手續費｜結匯", "國外交易結匯手續費", "expense-fees", 160),
        rule("public-rule-017", "現金消費｜自行提款", "現金自行提款", "expense-cash", 170),
        rule("public-rule-018", "交通｜高鐵", "高鐵智慧型手機", "expense-transport", 180),
        rule("public-rule-019", "交通｜停車服務", "停車大聲公", "expense-transport", 190),
    )

    private fun category(id: String, name: String, emoji: String, color: String, kind: CategoryKind) =
        Category(id = id, name = name, color = color, emoji = emoji, kind = kind)

    private fun rule(id: String, name: String, descriptionContains: String, categoryId: String, priority: Int) =
        AutoCategoryRule(
            id = id,
            name = name,
            descriptionContains = descriptionContains,
            direction = AutoCategoryRuleDirection.EXPENSE,
            categoryId = categoryId,
            priority = priority,
            descriptionMatchMode = AutoCategoryRuleDescriptionMatchMode.CONTAINS,
            isDefault = true,
        )
}
