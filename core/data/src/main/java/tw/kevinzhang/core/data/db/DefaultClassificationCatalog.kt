package tw.kevinzhang.core.data.db

import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleDirection
import tw.kevinzhang.core.data.model.AutoCategoryRuleAction
import tw.kevinzhang.core.data.model.AutoCategoryRuleCondition
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionField
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionGroup
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin
import tw.kevinzhang.core.data.model.AutoCategoryRuleSet
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CategoryKind
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Stable, public classification data bundled with the app.
 *
 * Category IDs and rule IDs are database identities: never derive them from user data or rename
 * an existing value. New public rules are appended to [publicAutoCategoryRules].
 */
object DefaultClassificationCatalog {
    /**
     * Public MCC mapping derived from Mastercard's Quick Reference Booklet and Visa's Merchant
     * Data Standards Manual. These codes identify merchant types, not individual transactions.
     */
    val publicMccRuleSet = AutoCategoryRuleSet(
        id = "public-mcc-rules-v2",
        name = "Moneylook public MCC rules v2",
        origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
        version = "2026.07.24",
        canonicalizerVersion = "v2-space-fold",
        contentSha256 = "60ad647811680cf1035d52f628c30db47a46e92fc1050b47aae369f412c94289",
    )

    /** Stable public rule IDs and MCCs. AUTO is reserved for one unambiguous category. */
    val publicMccRules: List<PublicMccRule> = listOf(
        mccRule("public-mcc-auto-expense-food-v2", "expense-food", AutoCategoryRuleAction.AUTO_APPLY, 100, "5411,5422,5441,5451,5462,5499,5811,5812,5814"),
        mccRule("public-mcc-auto-expense-clothing-v2", "expense-clothing", AutoCategoryRuleAction.AUTO_APPLY, 100, "5611,5621,5631,5641,5651,5655,5661,5681,5691,5697,5699"),
        mccRule("public-mcc-auto-expense-home-v2", "expense-home", AutoCategoryRuleAction.AUTO_APPLY, 100, "5200,5211,5231,5251,5261,5712,5713,5714,5718,5719,5722,7629,7641"),
        mccRule("public-mcc-auto-expense-transport-v2", "expense-transport", AutoCategoryRuleAction.AUTO_APPLY, 100, "4111,4112,4121,4131,4511,4784,4789,5532,5533,5542,5552,7512,7513,7523,7531,7534,7535,7538,7542,7549"),
        mccRule("public-mcc-auto-expense-medical-v2", "expense-medical", AutoCategoryRuleAction.AUTO_APPLY, 100, "4119,5975,5976,8011,8021,8031,8041,8042,8043,8049,8050,8062,8071,8099"),
        mccRule("public-mcc-auto-expense-learning-v2", "expense-learning", AutoCategoryRuleAction.AUTO_APPLY, 100, "8211,8220,8241,8244,8249,8299"),
        mccRule("public-mcc-auto-expense-entertainment-v2", "expense-entertainment", AutoCategoryRuleAction.AUTO_APPLY, 100, "5816,7832,7841,7922,7929,7932,7933,7941,7991,7992,7994,7996,7997,7998,7999"),
        mccRule("public-mcc-auto-expense-shopping-v2", "expense-shopping", AutoCategoryRuleAction.AUTO_APPLY, 100, "5262,5309,5310,5311,5331,5399,5732,5931,5941,5944,5946,5970,5973,5977,5993,5995,5997,5999"),
        mccRule("public-mcc-auto-expense-cash-v2", "expense-cash", AutoCategoryRuleAction.AUTO_APPLY, 100, "6010,6011"),
        mccRule("public-mcc-auto-expense-insurance-v2", "expense-insurance", AutoCategoryRuleAction.AUTO_APPLY, 100, "5960,6300"),
        mccRule("public-mcc-auto-expense-tax-v2", "expense-tax", AutoCategoryRuleAction.AUTO_APPLY, 100, "9311"),
        mccRule("public-mcc-auto-expense-phone-v2", "expense-phone", AutoCategoryRuleAction.AUTO_APPLY, 100, "4813"),
        mccRule("public-mcc-suggest-expense-phone-v2", "expense-phone", AutoCategoryRuleAction.SUGGEST, 50, "4814"),
        mccRule("public-mcc-suggest-expense-internet-v2", "expense-internet", AutoCategoryRuleAction.SUGGEST, 50, "4816"),
        mccRule("public-mcc-suggest-expense-entertainment-v2", "expense-entertainment", AutoCategoryRuleAction.SUGGEST, 50, "4899,5735,5813,5815"),
        mccRule("public-mcc-suggest-expense-home-v2", "expense-home", AutoCategoryRuleAction.SUGGEST, 50, "1520,1711,1731,1740,1750,1761,1771,1799,4900,5950,7217,7623"),
        mccRule("public-mcc-suggest-expense-transport-v2", "expense-transport", AutoCategoryRuleAction.SUGGEST, 50, "5541"),
        mccRule("public-mcc-suggest-expense-medical-v2", "expense-medical", AutoCategoryRuleAction.SUGGEST, 50, "5912"),
        mccRule("public-mcc-suggest-expense-shopping-v2", "expense-shopping", AutoCategoryRuleAction.SUGGEST, 50, "5932,5945"),
        mccRule("public-mcc-suggest-expense-gift-v2", "expense-gift", AutoCategoryRuleAction.SUGGEST, 50, "5947,5992"),
        mccRule("public-mcc-suggest-expense-topup-v2", "expense-topup", AutoCategoryRuleAction.SUGGEST, 50, "6540"),
        mccRule("public-mcc-suggest-expense-clothing-v2", "expense-clothing", AutoCategoryRuleAction.SUGGEST, 50, "7216,7251,7296"),
        mccRule("public-mcc-suggest-expense-learning-v2", "expense-learning", AutoCategoryRuleAction.SUGGEST, 50, "7911"),
        mccRule("public-mcc-suggest-expense-business-v2", "expense-business", AutoCategoryRuleAction.SUGGEST, 50, "7311,7333,7338,7339,7361,7372,7375,7379,7392,7399,8911,8931"),
        mccRule("public-mcc-suggest-transfer-account-v2", "transfer-account", AutoCategoryRuleAction.SUGGEST, 50, "4829"),
    )

    val publicStructuralRules: List<PublicMccRule> = listOf(
        phraseRule("public-structural-auto-credit-card-revolving-interest-v2", "expense-fees", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleDirection.EXPENSE, AssetKind.CREDIT_CARD, "循環利息", "revolving interest"),
        phraseRule("public-structural-auto-credit-card-installment-fee-v2", "expense-fees", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleDirection.EXPENSE, AssetKind.CREDIT_CARD, "信用卡分期手續費", "分期手續費", "installment fee"),
        phraseRule("public-structural-auto-credit-card-annual-fee-v2", "expense-fees", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleDirection.EXPENSE, AssetKind.CREDIT_CARD, "信用卡年費", "年費", "annual fee"),
        phraseRule("public-structural-auto-credit-card-foreign-fee-v2", "expense-fees", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleDirection.EXPENSE, AssetKind.CREDIT_CARD, "國外交易手續費", "foreign transaction fee"),
        phraseRule("public-structural-auto-deposit-cash-withdrawal-v2", "expense-cash", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, "跨行提款", "自行提款", "cash withdrawal", "atm withdrawal"),
        phraseRule("public-structural-auto-deposit-transfer-fee-v2", "expense-fees", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, "轉帳手續費", "transfer fee"),
        phraseRule("public-structural-suggest-salary-v2", "income-salary", AutoCategoryRuleAction.SUGGEST, AutoCategoryRuleDirection.INCOME, null, "薪資入帳", "薪水入帳", "salary"),
        phraseRule("public-structural-suggest-bonus-v2", "income-bonus", AutoCategoryRuleAction.SUGGEST, AutoCategoryRuleDirection.INCOME, null, "年終獎金", "績效獎金", "bonus"),
        phraseRule("public-structural-suggest-refund-v2", "income-refund", AutoCategoryRuleAction.SUGGEST, AutoCategoryRuleDirection.INCOME, null, "退貨退款", "交易退款", "refund"),
        phraseRule("public-structural-suggest-credit-card-payment-v2", "transfer-account", AutoCategoryRuleAction.SUGGEST, AutoCategoryRuleDirection.ANY, null, "信用卡繳款", "credit card payment", "card payment"),
        phraseRule("public-structural-suggest-auto-topup-v2", "expense-topup", AutoCategoryRuleAction.SUGGEST, AutoCategoryRuleDirection.EXPENSE, null, "自動加值", "自動儲值", "automatic top up", "auto top up"),
        phraseRule("public-structural-suggest-credit-card-installment-v2", "expense-shopping", AutoCategoryRuleAction.SUGGEST, AutoCategoryRuleDirection.EXPENSE, AssetKind.CREDIT_CARD, "信用卡分期消費", "installment purchase"),
    )

    /** Conservative, field-scoped public rules with no merchant or personal transaction data. */
    val publicStructuralRuleSet = AutoCategoryRuleSet(
        id = PUBLIC_STRUCTURAL_RULE_SET_ID,
        name = "Moneylook public structural rules v2",
        origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
        version = "2026.07.24",
        canonicalizerVersion = "v2-space-fold",
        contentSha256 = publicRuleCollectionContentSha256(publicStructuralRules),
    )

    /**
     * Publicly reviewed general-purpose phrases. This is deliberately an additive collection:
     * its identity is the migration marker, so a user's removal of a rule never causes a later
     * migration to restore it.
     */
    val publicGenericRuleSet: AutoCategoryRuleSet by lazy {
        AutoCategoryRuleSet(
            id = PUBLIC_GENERIC_RULE_SET_ID,
            name = "Moneylook public generic rules v3",
            origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
            version = "2026.07.24",
            canonicalizerVersion = "v2-space-fold",
            contentSha256 = publicRuleCollectionContentSha256(publicGenericRules),
        )
    }

    /**
     * Stable structured rules transcribed from the independently reviewed public candidate.
     * They intentionally use only generic transaction facts and broad merchant terminology.
     */
    val publicGenericRules: List<PublicMccRule> = listOf(
        genericRule("public-v2-transfer-mobile-noncontract", "帳戶移轉｜行動銀行轉帳", "transfer-account", AutoCategoryRuleDirection.ANY, AssetKind.DEPOSIT, 10, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "行銀非約跨"),
        genericRule("public-v2-transfer-mobile-interbank", "帳戶移轉｜行動銀行跨行轉帳", "transfer-account", AutoCategoryRuleDirection.ANY, AssetKind.DEPOSIT, 11, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "行銀跨行轉"),
        genericRule("public-v2-transfer-online-noncontract", "帳戶移轉｜網路非約轉帳", "transfer-account", AutoCategoryRuleDirection.ANY, AssetKind.DEPOSIT, 12, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "網路非約轉帳"),
        genericRule("public-v2-transfer-atm-interbank", "帳戶移轉｜ATM 跨行轉帳", "transfer-account", AutoCategoryRuleDirection.ANY, AssetKind.DEPOSIT, 13, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "ATM跨行轉"),
        genericRule("public-v2-transfer-corporate-intrabank", "帳戶移轉｜企業網銀本行轉帳", "transfer-account", AutoCategoryRuleDirection.ANY, AssetKind.DEPOSIT, 14, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "企網本行轉帳"),
        genericRule("public-v2-transfer-bank-debit", "帳戶移轉｜轉帳支取", "transfer-account", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 15, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "轉帳支取"),
        genericRule("public-v2-transfer-cd-credit", "帳戶移轉｜存提款機轉入", "transfer-account", AutoCategoryRuleDirection.INCOME, AssetKind.DEPOSIT, 16, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "CD轉入"),
        genericRule("public-v2-transfer-cd-debit", "帳戶移轉｜存提款機轉出", "transfer-account", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 17, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "CD轉出"),
        genericRule("public-v2-transfer-media", "帳戶移轉｜媒體轉帳", "transfer-account", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 18, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "媒體轉帳"),
        genericRule("public-v2-transfer-loan-payment", "帳戶移轉｜貸款帳戶轉入", "transfer-account", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 19, AutoCategoryRuleConditionField.MEMO, AutoCategoryRuleConditionMatchMode.CONTAINS, "轉入貸款帳號"),
        genericRule("public-v2-transfer-cash-deposit", "帳戶移轉｜現金存款", "transfer-account", AutoCategoryRuleDirection.INCOME, AssetKind.DEPOSIT, 20, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "現金存款"),
        genericRule("public-v2-transfer-credit-card-payment", "帳戶移轉｜信用卡款", "transfer-account", AutoCategoryRuleDirection.ANY, null, 21, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "信用卡款"),
        genericRule("public-v2-transfer-card-bill", "帳戶移轉｜卡費", "transfer-account", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 22, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "卡費"),
        genericRule("public-v2-transfer-card-bill-debit", "帳戶移轉｜卡款扣繳", "transfer-account", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 23, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "卡款扣繳"),
        genericRule("public-v2-fee-foreign-transaction", "費用｜國外交易服務費", "expense-fees", AutoCategoryRuleDirection.EXPENSE, AssetKind.CREDIT_CARD, 24, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "國外交易服務費"),
        genericRule("public-v2-fee-bank-service", "費用｜銀行手續費", "expense-fees", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 25, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.EXACT, "手續費"),
        genericRule("public-v2-cash-atm-withdrawal", "現金消費｜ATM 提款", "expense-cash", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 26, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "提款"),
        genericRule("public-v2-refund-card-purchase-offset", "退款｜信用卡消費折抵", "income-refund", AutoCategoryRuleDirection.INCOME, AssetKind.CREDIT_CARD, 27, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "信用卡消費折抵"),
        genericRule("public-v2-refund-fee-waiver", "退款｜費用減免", "income-refund", AutoCategoryRuleDirection.INCOME, AssetKind.CREDIT_CARD, 28, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "費減免"),
        genericRule("public-v2-internet-openai", "網路活動｜OpenAI", "expense-internet", AutoCategoryRuleDirection.EXPENSE, AssetKind.CREDIT_CARD, 29, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "OPENAI"),
        genericRule("public-v2-internet-claude", "網路活動｜Claude", "expense-internet", AutoCategoryRuleDirection.EXPENSE, AssetKind.CREDIT_CARD, 30, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "CLAUDE.AI"),
        genericRule("public-v2-internet-google-cloud", "網路活動｜Google Cloud", "expense-internet", AutoCategoryRuleDirection.EXPENSE, AssetKind.CREDIT_CARD, 31, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "GOOGLE CLOUD"),
        genericRule("public-v2-internet-google-one", "網路活動｜Google One", "expense-internet", AutoCategoryRuleDirection.EXPENSE, AssetKind.CREDIT_CARD, 32, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "GOOGLE ONE"),
        genericRule("public-v2-internet-cable-tv", "網路活動｜有線電視", "expense-internet", AutoCategoryRuleDirection.EXPENSE, null, 33, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "有線電視"),
        genericRule("public-v2-entertainment-steam", "休閒娛樂｜Steam", "expense-entertainment", AutoCategoryRuleDirection.EXPENSE, AssetKind.CREDIT_CARD, 34, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "STEAM"),
        genericRule("public-v2-transport-parking-platform", "交通｜停車服務", "expense-transport", AutoCategoryRuleDirection.EXPENSE, null, 35, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "停車大聲公"),
        genericRule("public-v2-transport-parking-operator", "交通｜停車服務", "expense-transport", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 36, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "正好停"),
        genericRule("public-v2-transport-mobility-platform", "交通｜共享機車", "expense-transport", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 37, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "威摩科技"),
        genericRule("public-v2-transport-fuel", "交通｜加油站", "expense-transport", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 38, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "加油站"),
        genericRule("public-v2-phone-mobile-service", "電信費｜行動通信", "expense-phone", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 39, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "台灣大哥大"),
        genericRule("public-v2-insurance-life", "保險｜人壽保險", "expense-insurance", AutoCategoryRuleDirection.EXPENSE, null, 40, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "人壽保險"),
        genericRule("public-v2-medical-pharmacy", "醫療｜藥局", "expense-medical", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 41, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "藥局"),
        genericRule("public-v2-learning-books", "學習｜金石堂", "expense-learning", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 42, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "金石堂"),
        genericRule("public-v2-home-ikea", "住家｜IKEA", "expense-home", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 43, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "宜家家居"),
        genericRule("public-v2-shopping-shopee", "購物｜蝦皮", "expense-shopping", AutoCategoryRuleDirection.EXPENSE, null, 44, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "蝦皮"),
        genericRule("public-v2-shopping-shopeepay", "購物｜ShopeePay", "expense-shopping", AutoCategoryRuleDirection.EXPENSE, AssetKind.CREDIT_CARD, 45, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "SHOPEEPAY"),
        genericRule("public-v2-shopping-department-store", "購物｜百貨公司", "expense-shopping", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 46, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "遠東百貨"),
        genericRule("public-v2-shopping-hypermarket", "購物｜量販店", "expense-shopping", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 47, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "家福股份有限公司"),
        genericRule("public-v2-shopping-digital-retail", "購物｜數位零售", "expense-shopping", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 48, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "三創數位"),
        genericRule("public-v2-food-convenience-store", "餐飲｜便利商店", "expense-food", AutoCategoryRuleDirection.EXPENSE, null, 49, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "便利商店"),
        genericRule("public-v2-food-mcdonalds", "餐飲｜麥當勞", "expense-food", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 50, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "和德昌"),
        genericRule("public-v2-food-mos-burger", "餐飲｜摩斯漢堡", "expense-food", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 51, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "安心食品"),
        genericRule("public-v2-food-matsuya", "餐飲｜松屋", "expense-food", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 52, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "台灣松屋"),
        genericRule("public-v2-food-komeda", "餐飲｜客美多咖啡", "expense-food", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 53, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "客美多"),
        genericRule("public-v2-food-ippudo", "餐飲｜一風堂", "expense-food", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 54, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "一風堂"),
        genericRule("public-v2-food-louisa", "餐飲｜路易莎咖啡", "expense-food", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 55, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "路易莎"),
        genericRule("public-v2-food-sukiya", "餐飲｜すき家", "expense-food", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 56, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "台灣善商"),
        genericRule("public-v2-food-kfc", "餐飲｜肯德基", "expense-food", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 57, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "富利餐飲"),
        genericRule("public-v2-food-50lan", "餐飲｜五十嵐", "expense-food", AutoCategoryRuleDirection.EXPENSE, AssetKind.DEPOSIT, 58, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "聯發國際餐飲"),
        genericRule("public-v2-food-tea-shop", "餐飲｜茶飲連鎖", "expense-food", AutoCategoryRuleDirection.EXPENSE, AssetKind.CREDIT_CARD, 59, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "茶之魔手"),
    )
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

    private fun mccRule(
        id: String,
        categoryId: String,
        action: AutoCategoryRuleAction,
        priority: Int,
        codes: String,
    ): PublicMccRule = PublicMccRule(
        rule = AutoCategoryRule(
            id = id,
            name = "MCC｜$categoryId",
            direction = AutoCategoryRuleDirection.EXPENSE,
            categoryId = categoryId,
            priority = priority,
            isDefault = true,
            ruleSetId = publicMccRuleSet.id,
            accountKind = AssetKind.CREDIT_CARD,
            origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
            action = action,
        ),
        conditions = codes.split(',').mapIndexed { position, code ->
            AutoCategoryRuleCondition(
                ruleId = id,
                position = position,
                conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                field = AutoCategoryRuleConditionField.MERCHANT_CATEGORY_CODE,
                matchMode = AutoCategoryRuleConditionMatchMode.EXACT,
                pattern = code,
            )
        },
    )

    private fun phraseRule(
        id: String,
        categoryId: String,
        action: AutoCategoryRuleAction,
        direction: AutoCategoryRuleDirection,
        accountKind: AssetKind?,
        vararg phrases: String,
    ): PublicMccRule = PublicMccRule(
        rule = AutoCategoryRule(
            id = id,
            name = "結構化｜$categoryId",
            direction = direction,
            categoryId = categoryId,
            priority = if (action == AutoCategoryRuleAction.AUTO_APPLY) 100 else 50,
            isDefault = true,
            ruleSetId = PUBLIC_STRUCTURAL_RULE_SET_ID,
            accountKind = accountKind,
            origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
            action = action,
        ),
        conditions = phrases.flatMapIndexed { phraseIndex, phrase ->
            listOf(
                AutoCategoryRuleConditionField.DESCRIPTION,
                AutoCategoryRuleConditionField.MEMO,
                AutoCategoryRuleConditionField.TYPE,
            ).mapIndexed { fieldIndex, field ->
                AutoCategoryRuleCondition(
                    ruleId = id,
                    position = phraseIndex * STRUCTURAL_TEXT_FIELDS + fieldIndex,
                    conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    field = field,
                    matchMode = AutoCategoryRuleConditionMatchMode.CONTAINS,
                    pattern = phrase,
                )
            }
        },
    )

    private fun genericRule(
        id: String,
        name: String,
        categoryId: String,
        direction: AutoCategoryRuleDirection,
        accountKind: AssetKind?,
        priority: Int,
        field: AutoCategoryRuleConditionField,
        matchMode: AutoCategoryRuleConditionMatchMode,
        pattern: String,
    ): PublicMccRule = PublicMccRule(
        rule = AutoCategoryRule(
            id = id,
            name = name,
            direction = direction,
            categoryId = categoryId,
            priority = priority,
            isDefault = true,
            ruleSetId = PUBLIC_GENERIC_RULE_SET_ID,
            accountKind = accountKind,
            origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
            action = AutoCategoryRuleAction.AUTO_APPLY,
        ),
        conditions = listOf(
            AutoCategoryRuleCondition(
                ruleId = id,
                position = 0,
                conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                field = field,
                matchMode = matchMode,
                pattern = pattern,
            ),
        ),
    )

    private const val PUBLIC_STRUCTURAL_RULE_SET_ID = "public-structural-rules-v2"
    const val PUBLIC_GENERIC_RULE_SET_ID = "public-generic-rules-v3"
    private const val STRUCTURAL_TEXT_FIELDS = 3
}

data class PublicMccRule(
    val rule: AutoCategoryRule,
    val conditions: List<AutoCategoryRuleCondition>,
)

internal fun publicRuleCollectionContentSha256(rules: List<PublicMccRule>): String {
    val csv = AutoCategoryRuleCsvCodec.encodeV2(
        AutoCategoryRuleCsvImport(
            rules = rules.map(PublicMccRule::rule),
            conditionsByRuleId = rules.associate { it.rule.id to it.conditions },
        ),
    )
    return MessageDigest.getInstance("SHA-256")
        .digest(csv.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
