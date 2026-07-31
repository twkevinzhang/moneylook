package tw.kevinzhang.core.data.db

import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleAmountSign
import tw.kevinzhang.core.data.model.AutoCategoryRuleAction
import tw.kevinzhang.core.data.model.AutoCategoryRuleCondition
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionField
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionGroup
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin
import tw.kevinzhang.core.data.model.AutoCategoryRuleSet
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CategoryReportingGroup
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
        mccRule("public-mcc-suggest-expense-phone-v2", "expense-phone", AutoCategoryRuleAction.AUTO_APPLY, 50, "4814"),
        mccRule("public-mcc-suggest-expense-internet-v2", "expense-internet", AutoCategoryRuleAction.AUTO_APPLY, 50, "4816"),
        mccRule("public-mcc-suggest-expense-entertainment-v2", "expense-entertainment", AutoCategoryRuleAction.AUTO_APPLY, 50, "4899,5735,5813,5815"),
        mccRule("public-mcc-suggest-expense-home-v2", "expense-home", AutoCategoryRuleAction.AUTO_APPLY, 50, "1520,1711,1731,1740,1750,1761,1771,1799,4900,5950,7217,7623"),
        mccRule("public-mcc-suggest-expense-transport-v2", "expense-transport", AutoCategoryRuleAction.AUTO_APPLY, 50, "5541"),
        mccRule("public-mcc-suggest-expense-medical-v2", "expense-medical", AutoCategoryRuleAction.AUTO_APPLY, 50, "5912"),
        mccRule("public-mcc-suggest-expense-shopping-v2", "expense-shopping", AutoCategoryRuleAction.AUTO_APPLY, 50, "5932,5945"),
        mccRule("public-mcc-suggest-expense-gift-v2", "expense-gift", AutoCategoryRuleAction.AUTO_APPLY, 50, "5947,5992"),
        mccRule("public-mcc-suggest-expense-topup-v2", "expense-topup", AutoCategoryRuleAction.AUTO_APPLY, 50, "6540"),
        mccRule("public-mcc-suggest-expense-clothing-v2", "expense-clothing", AutoCategoryRuleAction.AUTO_APPLY, 50, "7216,7251,7296"),
        mccRule("public-mcc-suggest-expense-learning-v2", "expense-learning", AutoCategoryRuleAction.AUTO_APPLY, 50, "7911"),
        mccRule("public-mcc-suggest-expense-business-v2", "expense-business", AutoCategoryRuleAction.AUTO_APPLY, 50, "7311,7333,7338,7339,7361,7372,7375,7379,7392,7399,8911,8931"),
        mccRule("public-mcc-suggest-transfer-account-v2", "transfer-account", AutoCategoryRuleAction.AUTO_APPLY, 50, "4829"),
    )

    val publicMccRuleSet: AutoCategoryRuleSet by lazy {
        AutoCategoryRuleSet(
            id = PUBLIC_MCC_RULE_SET_ID,
            name = "Moneylook public MCC rules v2",
            origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
            version = "2026.07.27",
            canonicalizerVersion = "v2-space-fold",
            contentSha256 = publicRuleCollectionContentSha256(publicMccRules),
        )
    }

    val publicStructuralRules: List<PublicMccRule> = listOf(
        phraseRule("public-structural-auto-credit-card-revolving-interest-v2", "expense-fees", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, "循環利息", "revolving interest"),
        phraseRule("public-structural-auto-credit-card-installment-fee-v2", "expense-fees", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, "信用卡分期手續費", "分期手續費", "installment fee"),
        annualFeeRule(),
        phraseRule("public-structural-auto-credit-card-foreign-fee-v2", "expense-fees", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, "國外交易手續費", "foreign transaction fee"),
        phraseRule("public-structural-auto-deposit-transfer-fee-v2", "expense-fees", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, "轉帳手續費", "transfer fee"),
        phraseRule("public-structural-suggest-salary-v2", "income-salary", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleAmountSign.POSITIVE, null, "薪資入帳", "薪水入帳", "salary"),
        phraseRule("public-structural-suggest-bonus-v2", "income-bonus", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleAmountSign.POSITIVE, null, "年終獎金", "績效獎金", "bonus"),
        phraseRule("public-structural-suggest-refund-v2", "income-refund", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleAmountSign.POSITIVE, null, "退貨退款", "交易退款", "refund"),
        phraseRule("public-structural-suggest-credit-card-payment-v2", "transfer-account", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleAmountSign.ANY, null, "信用卡繳款", "credit card payment", "card payment"),
        phraseRule("public-structural-suggest-auto-topup-v2", "expense-topup", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleAmountSign.NEGATIVE, null, "自動加值", "自動儲值", "automatic top up", "auto top up"),
        phraseRule("public-structural-suggest-credit-card-installment-v2", "expense-shopping", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, "信用卡分期消費", "installment purchase"),
        phraseRule("public-structural-auto-deposit-interest-v3", "income-interest", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleAmountSign.POSITIVE, AssetKind.DEPOSIT, "存款利息", "優惠利息", "存款息", "利息"),
        phraseRule("public-structural-auto-credit-card-cashback-v3", "income-cashback", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleAmountSign.POSITIVE, AssetKind.CREDIT_CARD, "刷卡現金回饋", "現金回饋"),
        phraseRule("public-structural-auto-easycard-topup-v3", "transfer-account", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleAmountSign.ANY, null, "代扣：悠遊儲值", "代扣:悠遊儲值", "代扣悠遊儲值"),
        phraseRule("public-structural-auto-deposit-atm-cdm-cash-deposit-v3", "transfer-account", AutoCategoryRuleAction.AUTO_APPLY, AutoCategoryRuleAmountSign.POSITIVE, AssetKind.DEPOSIT, "ATM存", "ATM存款", "CDM存款", "ATM現金存入", "CDM現金存入", "現金存入"),
    )

    /** Conservative, field-scoped public rules with no merchant or personal transaction data. */
    val publicStructuralRuleSet = AutoCategoryRuleSet(
        id = PUBLIC_STRUCTURAL_RULE_SET_ID,
        name = "Moneylook public structural rules v3",
        origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
        version = "2026.08.01",
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
            name = "Moneylook public generic rules v7",
            origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
            version = "2026.08.01",
            canonicalizerVersion = "v2-space-fold",
            contentSha256 = publicRuleCollectionContentSha256(publicGenericRules),
        )
    }

    /**
     * Stable structured rules transcribed from the independently reviewed public candidate.
     * They intentionally use only generic transaction facts and broad merchant terminology.
     */
    val publicGenericRules: List<PublicMccRule> = listOf(
        genericRule("public-v2-transfer-mobile-noncontract", "帳戶移轉｜行動銀行轉帳", "transfer-account", AutoCategoryRuleAmountSign.ANY, AssetKind.DEPOSIT, 10, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "行銀非約跨"),
        genericRule("public-v2-transfer-mobile-interbank", "帳戶移轉｜行動銀行跨行轉帳", "transfer-account", AutoCategoryRuleAmountSign.ANY, AssetKind.DEPOSIT, 11, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "行銀跨行轉"),
        genericRule("public-v2-transfer-online-noncontract", "帳戶移轉｜網路非約轉帳", "transfer-account", AutoCategoryRuleAmountSign.ANY, AssetKind.DEPOSIT, 12, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "網路非約轉帳"),
        genericRule("public-v2-transfer-atm-interbank", "帳戶移轉｜ATM 跨行轉帳", "transfer-account", AutoCategoryRuleAmountSign.ANY, AssetKind.DEPOSIT, 13, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "ATM跨行轉"),
        genericRule("public-v2-transfer-corporate-intrabank", "帳戶移轉｜企業網銀本行轉帳", "transfer-account", AutoCategoryRuleAmountSign.ANY, AssetKind.DEPOSIT, 14, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "企網本行轉帳"),
        genericRule("public-v2-transfer-bank-debit", "帳戶移轉｜轉帳支取", "transfer-account", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 15, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "轉帳支取"),
        genericRule("public-v2-transfer-cd-credit", "帳戶移轉｜存提款機轉入", "transfer-account", AutoCategoryRuleAmountSign.POSITIVE, AssetKind.DEPOSIT, 16, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "CD轉入"),
        genericRule("public-v2-transfer-cd-debit", "帳戶移轉｜存提款機轉出", "transfer-account", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 17, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "CD轉出"),
        genericRule("public-v2-transfer-media", "帳戶移轉｜媒體轉帳", "transfer-account", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 18, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "媒體轉帳"),
        genericRule("public-v2-transfer-loan-payment", "帳戶移轉｜貸款帳戶轉入", "transfer-account", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 19, AutoCategoryRuleConditionField.MEMO, AutoCategoryRuleConditionMatchMode.CONTAINS, "轉入貸款帳號"),
        genericRule("public-v2-transfer-cash-deposit", "帳戶移轉｜現金存款", "transfer-account", AutoCategoryRuleAmountSign.POSITIVE, AssetKind.DEPOSIT, 20, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "現金存款"),
        genericRule("public-v2-transfer-credit-card-payment", "帳戶移轉｜信用卡款", "transfer-account", AutoCategoryRuleAmountSign.ANY, null, 21, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "信用卡款"),
        genericRule("public-v2-transfer-card-bill", "帳戶移轉｜卡費", "transfer-account", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 22, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "卡費"),
        genericRule("public-v2-transfer-card-bill-debit", "帳戶移轉｜卡款扣繳", "transfer-account", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 23, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "卡款扣繳"),
        genericRule("public-v2-fee-foreign-transaction", "費用｜國外交易服務費", "expense-fees", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, 24, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "國外交易服務費"),
        genericRule("public-v2-fee-bank-service", "費用｜銀行手續費", "expense-fees", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 25, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.EXACT, "手續費"),
        genericRule("public-v2-cash-atm-withdrawal", "現金消費｜ATM 提款", "expense-cash", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 26, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "提款"),
        genericRule("public-v2-refund-card-purchase-offset", "退款｜信用卡消費折抵", "income-refund", AutoCategoryRuleAmountSign.POSITIVE, AssetKind.CREDIT_CARD, 27, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "信用卡消費折抵"),
        genericRule("public-v2-refund-fee-waiver", "退款｜費用減免", "income-refund", AutoCategoryRuleAmountSign.POSITIVE, AssetKind.CREDIT_CARD, 28, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "費減免"),
        genericRule("public-v2-internet-openai", "網路活動｜OpenAI", "expense-internet", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, 29, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "OPENAI"),
        genericRule("public-v2-internet-claude", "網路活動｜Claude", "expense-internet", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, 30, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "CLAUDE.AI"),
        genericRule("public-v2-internet-google-cloud", "網路活動｜Google Cloud", "expense-internet", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, 31, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "GOOGLE CLOUD"),
        genericRule("public-v2-internet-google-one", "網路活動｜Google One", "expense-internet", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, 32, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "GOOGLE ONE"),
        genericRule("public-v2-internet-cable-tv", "網路活動｜有線電視", "expense-internet", AutoCategoryRuleAmountSign.NEGATIVE, null, 33, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "有線電視"),
        genericRule("public-v2-entertainment-steam", "休閒娛樂｜Steam", "expense-entertainment", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, 34, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "STEAM"),
        genericRule("public-v2-transport-parking-platform", "交通｜停車服務", "expense-transport", AutoCategoryRuleAmountSign.NEGATIVE, null, 35, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "停車大聲公"),
        genericRule("public-v2-transport-parking-operator", "交通｜停車服務", "expense-transport", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 36, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "正好停"),
        genericRule("public-v2-transport-mobility-platform", "交通｜共享機車", "expense-transport", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 37, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "威摩科技"),
        genericRule("public-v2-transport-fuel", "交通｜加油站", "expense-transport", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 38, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "加油站"),
        genericRule("public-v2-phone-mobile-service", "電信費｜行動通信", "expense-phone", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 39, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "台灣大哥大"),
        genericRule("public-v2-insurance-life", "保險｜人壽保險", "expense-insurance", AutoCategoryRuleAmountSign.NEGATIVE, null, 40, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "人壽保險"),
        genericRule("public-v2-medical-pharmacy", "醫療｜藥局", "expense-medical", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 41, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "藥局"),
        genericRule("public-v2-learning-books", "學習｜金石堂", "expense-learning", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 42, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "金石堂"),
        genericRule("public-v2-home-ikea", "住家｜IKEA", "expense-home", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 43, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "宜家家居"),
        genericRule("public-v2-shopping-shopee", "購物｜蝦皮", "expense-shopping", AutoCategoryRuleAmountSign.NEGATIVE, null, 44, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "蝦皮"),
        genericRule("public-v2-shopping-shopeepay", "購物｜ShopeePay", "expense-shopping", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, 45, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "SHOPEEPAY"),
        genericRule("public-v2-shopping-department-store", "購物｜百貨公司", "expense-shopping", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 46, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "遠東百貨"),
        genericRule("public-v2-shopping-hypermarket", "購物｜量販店", "expense-shopping", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 47, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "家福股份有限公司"),
        genericRule("public-v2-shopping-digital-retail", "購物｜數位零售", "expense-shopping", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 48, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "三創數位"),
        genericRule("public-v2-food-convenience-store", "餐飲｜便利商店", "expense-food", AutoCategoryRuleAmountSign.NEGATIVE, null, 49, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "便利商店"),
        genericRule("public-v2-food-mcdonalds", "餐飲｜麥當勞", "expense-food", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 50, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "和德昌"),
        genericRule("public-v2-food-mos-burger", "餐飲｜摩斯漢堡", "expense-food", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 51, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "安心食品"),
        genericRule("public-v2-food-matsuya", "餐飲｜松屋", "expense-food", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 52, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "台灣松屋"),
        genericRule("public-v2-food-komeda", "餐飲｜客美多咖啡", "expense-food", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 53, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "客美多"),
        genericRule("public-v2-food-ippudo", "餐飲｜一風堂", "expense-food", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 54, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "一風堂"),
        genericRule("public-v2-food-louisa", "餐飲｜路易莎咖啡", "expense-food", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 55, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "路易莎"),
        genericRule("public-v2-food-sukiya", "餐飲｜すき家", "expense-food", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 56, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "台灣善商"),
        genericRule("public-v2-food-kfc", "餐飲｜肯德基", "expense-food", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 57, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "富利餐飲"),
        genericRule("public-v2-food-50lan", "餐飲｜五十嵐", "expense-food", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 58, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "聯發國際餐飲"),
        genericRule("public-v2-food-tea-shop", "餐飲｜茶飲連鎖", "expense-food", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, 59, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "茶之魔手"),
        genericRule("public-v3-cash-atm-withdrawal-aliases", "現金消費｜ATM 提款別名", "expense-cash", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 26, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "跨提", "現金提", "ATM提"),
        genericRule("public-v3-entertainment-steam", "休閒娛樂｜Steam", "expense-entertainment", AutoCategoryRuleAmountSign.NEGATIVE, null, 34, AutoCategoryRuleConditionField.SEARCHABLE_TEXT, AutoCategoryRuleConditionMatchMode.CONTAINS, "STEAM"),
        genericRule("public-v3-insurance-corporate", "保險｜保險公司", "expense-insurance", AutoCategoryRuleAmountSign.NEGATIVE, null, 40, AutoCategoryRuleConditionField.SEARCHABLE_TEXT, AutoCategoryRuleConditionMatchMode.CONTAINS, "保險股份有限公司"),
        genericRule("public-v3-fee-foreign-transaction", "費用｜國外交易手續費", "expense-fees", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 24, AutoCategoryRuleConditionField.SEARCHABLE_TEXT, AutoCategoryRuleConditionMatchMode.CONTAINS, "國外交易手續費"),
        genericRule("public-v3-income-interest-exact", "收入｜利息", "income-interest", AutoCategoryRuleAmountSign.POSITIVE, AssetKind.DEPOSIT, 20, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.EXACT, "利息"),
        genericRule("public-v4-transfer-linked-account", "帳戶移轉｜連結帳戶交易", "transfer-account", AutoCategoryRuleAmountSign.ANY, AssetKind.DEPOSIT, 5, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "連結帳戶交易"),
        genericRule("public-v4-transfer-bank-aliases", "帳戶移轉｜銀行轉帳別名", "transfer-account", AutoCategoryRuleAmountSign.ANY, AssetKind.DEPOSIT, 6, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "網路本行轉帳", "網銀跨行轉帳", "網銀轉帳", "跨行轉", "跨轉", "轉帳提", "轉帳存", "ATMF 轉入", "FXML入帳", "網際跨行轉帳更正", "扣押解", "開戶"),
        genericRule("public-v4-transfer-credit-card-payment-text", "帳戶移轉｜信用卡繳款文字", "transfer-account", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.DEPOSIT, 7, AutoCategoryRuleConditionField.SEARCHABLE_TEXT, AutoCategoryRuleConditionMatchMode.CONTAINS, "信用卡"),
        genericRule("public-v4-transfer-deposit-reversal", "帳戶移轉｜存款沖正", "transfer-account", AutoCategoryRuleAmountSign.POSITIVE, AssetKind.DEPOSIT, 8, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "沖正", "更正"),
        genericRule("public-v4-transfer-easycard-redemption", "帳戶移轉｜悠遊卡停卡贖回", "transfer-account", AutoCategoryRuleAmountSign.ANY, null, 9, AutoCategoryRuleConditionField.SEARCHABLE_TEXT, AutoCategoryRuleConditionMatchMode.CONTAINS, "悠遊卡停卡贖回"),
        genericRule("public-v4-transfer-bank-payment-network", "帳戶移轉｜銀行繳費網", "transfer-account", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, 10, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "全國繳費網－凱基銀行"),
        digitalWalletRule(),
        scopeRule("public-v4-income-credit-card-refund-fallback", "退款｜正額信用卡交易", "income-refund", AutoCategoryRuleAmountSign.POSITIVE, AssetKind.CREDIT_CARD, 80),
        genericRule("public-v4-income-deposit-fee-refund", "退款｜存款手續費退回", "income-refund", AutoCategoryRuleAmountSign.POSITIVE, AssetKind.DEPOSIT, 81, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "手續費"),
        genericRule("public-v4-income-employment-training-subsidy", "收入｜就保職訓補助", "income-subsidy", AutoCategoryRuleAmountSign.POSITIVE, AssetKind.DEPOSIT, 82, AutoCategoryRuleConditionField.SEARCHABLE_TEXT, AutoCategoryRuleConditionMatchMode.CONTAINS, "就保職訓"),
        genericRule("public-v4-fees-card-penalties", "費用｜信用卡利息與違約金", "expense-fees", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, 83, AutoCategoryRuleConditionField.SEARCHABLE_TEXT, AutoCategoryRuleConditionMatchMode.CONTAINS, "循環信用利息", "遲延繳款違約金"),
        genericRule("public-v4-phone-chunghwa-telecom", "電信費｜中華電信", "expense-phone", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, 84, AutoCategoryRuleConditionField.SEARCHABLE_TEXT, AutoCategoryRuleConditionMatchMode.CONTAINS, "中華電信"),
        genericRule("public-v4-internet-google-services", "網路活動｜Google 服務", "expense-internet", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, 85, AutoCategoryRuleConditionField.SEARCHABLE_TEXT, AutoCategoryRuleConditionMatchMode.CONTAINS, "GOOGLE DIGIBITES", "GOOGLE LINE CALLS"),
        genericRule("public-v4-entertainment-animation", "休閒娛樂｜動畫服務", "expense-entertainment", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, 86, AutoCategoryRuleConditionField.SEARCHABLE_TEXT, AutoCategoryRuleConditionMatchMode.CONTAINS, "動畫瘋"),
        genericRule("public-v4-transport-go-share", "交通｜GoShare", "expense-transport", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, 87, AutoCategoryRuleConditionField.SEARCHABLE_TEXT, AutoCategoryRuleConditionMatchMode.CONTAINS, "GOPOCKET", "GO SHARE"),
        genericRule("public-v4-insurance-national-health", "保險｜健保費", "expense-insurance", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, 88, AutoCategoryRuleConditionField.SEARCHABLE_TEXT, AutoCategoryRuleConditionMatchMode.CONTAINS, "中央健康保險署"),
        genericRule("public-v4-food-mcdonalds-display-name", "餐飲｜麥當勞顯示名稱", "expense-food", AutoCategoryRuleAmountSign.NEGATIVE, AssetKind.CREDIT_CARD, 90, AutoCategoryRuleConditionField.SEARCHABLE_TEXT, AutoCategoryRuleConditionMatchMode.CONTAINS, "台灣麥當勞"),
        // V5 is intentionally merchant-name scoped.  Bank display text and payment-provider
        // prefixes are not merchant identities, so these rules do not inspect SEARCHABLE_TEXT.
        merchantRule("public-v5-food-sukiya", "餐飲｜SUKIYA", "expense-food", "SUKIYA", "すき家", "台灣善商"),
        merchantRule("public-v5-food-komeda", "餐飲｜客美多", "expense-food", "客美多"),
        merchantRule("public-v5-food-matsuya", "餐飲｜松屋", "expense-food", "松屋"),
        merchantRule("public-v5-food-kebuke", "餐飲｜可不可熟成紅茶", "expense-food", "可不可熟成紅茶"),
        merchantRule("public-v5-food-pizzahut", "餐飲｜必勝客", "expense-food", "必勝客"),
        merchantRule("public-v5-food-sato", "餐飲｜佐藤精肉店", "expense-food", "佐藤精肉店"),
        merchantRule("public-v5-food-saizeriya", "餐飲｜薩莉亞", "expense-food", "薩莉亞"),
        merchantRule("public-v5-food-bafang", "餐飲｜八方雲集", "expense-food", "八方雲集"),
        merchantRule("public-v5-food-xindian", "餐飲｜辛殿麻辣鍋", "expense-food", "辛殿麻辣鍋"),
        merchantRule("public-v5-food-hikari", "餐飲｜光鮨迴轉壽司", "expense-food", "光鮨迴轉壽司"),
        merchantRule("public-v5-food-daoting", "餐飲｜稻町家", "expense-food", "稻町家"),
        merchantRule("public-v5-food-kayiyi", "餐飲｜咖一味", "expense-food", "咖一味"),
        merchantRule("public-v5-food-torijin", "餐飲｜鳥人拉麵", "expense-food", "鳥人拉麵"),
        merchantRule("public-v5-food-su", "餐飲｜蘇氏麵館", "expense-food", "蘇氏麵館"),
        merchantRule("public-v5-food-zen", "餐飲｜禪風茶樓", "expense-food", "禪風茶樓"),
        merchantRule("public-v5-food-ippudo", "餐飲｜一風堂", "expense-food", "一風堂"),
        merchantRule("public-v5-food-louisa", "餐飲｜LOUISA COFFEE", "expense-food", "LOUISA COFFEE"),
        merchantRule("public-v5-food-starbucks", "餐飲｜星巴克", "expense-food", "星巴克"),
        merchantRule("public-v5-food-qiaozhiwei", "餐飲｜巧之味", "expense-food", "巧之味"),
        merchantRule("public-v5-food-chiyuan", "餐飲｜季緣", "expense-food", "季緣"),
        merchantAllRule("public-v5-food-att4fun", "餐飲｜ATT 4 FUN 餐廳", "expense-food", "ATT 4 FUN", "餐廳"),
        merchantAllRule("public-v5-food-teahouse-sanchuang", "餐飲｜茶屋", "expense-food", "茶屋", "三創店"),
        merchantRule("public-v5-shopping-jinhua", "購物｜今華電子", "expense-shopping", "今華電子"),
        merchantRule("public-v5-shopping-shengsheng", "購物｜勝勝小舖", "expense-shopping", "勝勝小舖"),
        merchantRule("public-v5-shopping-dream", "購物｜情趣夢天堂", "expense-shopping", "情趣夢天堂"),
        merchantRule("public-v5-shopping-shenghua", "購物｜勝華百貨行", "expense-shopping", "勝華百貨行"),
        merchantRule("public-v5-shopping-shengyue", "購物｜勝越企業社", "expense-shopping", "勝越企業社"),
        merchantRule("public-v5-shopping-sanchuang", "購物｜三創數位", "expense-shopping", "三創數位"),
        merchantRule("public-v5-shopping-pchome", "購物｜PCHOME", "expense-shopping", "PCHOME"),
        merchantRule("public-v5-shopping-carrefour", "購物｜家樂福", "expense-shopping", "家樂福"),
        merchantRule("public-v5-shopping-rtmart", "購物｜大全聯", "expense-shopping", "大全聯"),
        merchantRule("public-v5-shopping-jsf", "購物｜金興發", "expense-shopping", "金興發"),
        merchantRule("public-v5-shopping-yuanda", "購物｜源達科技", "expense-shopping", "源達科技"),
        merchantRule("public-v5-shopping-nihonbashi", "購物｜日本橋3C", "expense-shopping", "日本橋3C"),
        merchantRule("public-v5-shopping-xiangchang", "購物｜祥昌電子", "expense-shopping", "祥昌電子"),
        merchantRule("public-v5-shopping-miramar", "購物｜美麗華百樂園", "expense-shopping", "美麗華百樂園"),
        merchantRule("public-v5-shopping-zhouquan", "購物｜洲全生活館", "expense-shopping", "洲全生活館"),
        merchantRule("public-v5-transport-guoyuan", "交通｜國園加油站", "expense-transport", "國園加油站"),
        merchantRule("public-v5-transport-sunshine", "交通｜陽光市民加油站", "expense-transport", "陽光市民加油站"),
        merchantRule("public-v5-transport-cpc", "交通｜中油", "expense-transport", "中油"),
        merchantAllRule("public-v5-transport-uniparking", "交通｜統一精工停車", "expense-transport", "統一精工", "停車"),
        merchantRule("public-v5-stationery-101", "文具｜101文具天堂", "expense-stationery", "101文具天堂"),
        merchantRule("public-v5-stationery-printing", "文具｜經典數位印刷", "expense-stationery", "經典數位印刷"),
        merchantRule("public-v5-home-ikea", "住家｜IKEA", "expense-home", "IKEA", "宜家家居"),
        merchantRule("public-v5-home-zhenyu", "住家｜振宇五金", "expense-home", "振宇五金"),
        merchantRule("public-v5-clothing-net", "服飾｜NET", "expense-clothing", "NET忠孝"),
        merchantRule("public-v5-entertainment-lihua", "休閒娛樂｜麗華行電競旗艦館", "expense-entertainment", "麗華行電競旗艦館"),
        genericRule("public-v5-internet-squarespace", "網路活動｜Squarespace", "expense-internet", AutoCategoryRuleAmountSign.NEGATIVE, null, 28, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.CONTAINS, "SQSP* DOMAIN"),
        merchantRule("public-v5-medical-pharmacy", "醫療｜藥局", "expense-medical", "藥局"),
    )
    val categories: List<Category> = listOf(
        category("expense-food", "餐飲", "🍽️", "#FB8C00", CategoryReportingGroup.EXPENSE),
        category("expense-clothing", "服飾", "👕", "#F9C928", CategoryReportingGroup.EXPENSE),
        category("expense-home", "住家", "🏠", "#9CD948", CategoryReportingGroup.EXPENSE),
        category("expense-transport", "交通", "🚌", "#3E8EEA", CategoryReportingGroup.EXPENSE),
        category("expense-learning", "學習", "📘", "#4169E1", CategoryReportingGroup.EXPENSE),
        category("expense-entertainment", "休閒娛樂", "🎮", "#8739E8", CategoryReportingGroup.EXPENSE),
        category("expense-shopping", "購物", "🛒", "#45C7E8", CategoryReportingGroup.EXPENSE),
        category("expense-medical", "醫療", "🩺", "#EF5350", CategoryReportingGroup.EXPENSE),
        category("expense-cash", "現金消費", "💵", "#43B96D", CategoryReportingGroup.EXPENSE),
        category("expense-insurance", "保險", "🛡️", "#EC80BD", CategoryReportingGroup.EXPENSE),
        category("expense-fees", "費用/手續費", "💸", "#66C94D", CategoryReportingGroup.EXPENSE),
        category("expense-tax", "稅金", "🧾", "#109C91", CategoryReportingGroup.EXPENSE),
        category("expense-gift", "禮物", "🎁", "#EF5350", CategoryReportingGroup.EXPENSE),
        category("expense-business", "合夥生意", "🍻", "#EF5350", CategoryReportingGroup.EXPENSE),
        category("expense-phone", "電信費", "📞", "#3F63D8", CategoryReportingGroup.EXPENSE),
        category("expense-internet", "網路活動", "🖥️", "#8439E9", CategoryReportingGroup.EXPENSE),
        category("expense-topup", "儲值", "🍴", "#4169E1", CategoryReportingGroup.EXPENSE),
        category("expense-ipass", "iPASS 儲值", "🎫", "#72C95B", CategoryReportingGroup.EXPENSE),
        category("expense-jkopay", "街口儲值", "🎟️", "#EF5350", CategoryReportingGroup.EXPENSE),
        category("expense-dispute", "爭議", "🐾", "#EF5350", CategoryReportingGroup.EXPENSE),
        category("expense-digital-wallet", "電子支付", "📱", "#26A69A", CategoryReportingGroup.EXPENSE),
        category("expense-stationery", "文具", "✏️", "#5B75C9", CategoryReportingGroup.EXPENSE),
        category("income-salary", "薪資", "💰", "#43B96D", CategoryReportingGroup.INCOME),
        category("income-bonus", "獎金", "✨", "#F9C928", CategoryReportingGroup.INCOME),
        category("income-refund", "退款", "↩️", "#3E8EEA", CategoryReportingGroup.INCOME),
        category("income-interest", "利息收入", "💹", "#4CAF50", CategoryReportingGroup.INCOME),
        category("income-cashback", "現金回饋", "🎉", "#FF9800", CategoryReportingGroup.INCOME),
        category("income-subsidy", "補助收入", "🤝", "#7CB342", CategoryReportingGroup.INCOME),
        category("transfer-account", "帳戶移轉", "🔄", "#607D8B", CategoryReportingGroup.EXCLUDED),
    )

    /**
     * Reviewed, broadly reusable rules only. These IDs deliberately remain stable across releases,
     * and every description is a reviewed generic fragment without private transaction context.
     */
    val publicAutoCategoryRules: List<AutoCategoryRule> = listOf(
        rule("public-rule-001", "儲值｜LINE Pay Money", "line pay money", "expense-topup", 10),
        rule("public-rule-002", "餐飲｜foodpanda", "foodpanda", "expense-food", 20),
        rule("public-rule-004", "交通｜YouBike", "youbike", "expense-transport", 40),
        rule("public-rule-005", "網路活動｜GitHub", "github", "expense-internet", 50),
        rule("public-rule-012", "現金消費｜卡片提款", "卡片提款", "expense-cash", 120),
        rule("public-rule-013", "交通｜加油站", "加油站", "expense-transport", 130),
        rule("public-rule-014", "交通｜停車服務", "日月亭", "expense-transport", 140),
        rule("public-rule-016", "費用／手續費｜結匯", "國外交易結匯手續費", "expense-fees", 160),
        rule("public-rule-018", "交通｜高鐵", "高鐵智慧型手機", "expense-transport", 180),
    )

    private fun category(
        id: String,
        name: String,
        emoji: String,
        color: String,
        reportingGroup: CategoryReportingGroup,
    ) = Category(id = id, name = name, color = color, emoji = emoji, reportingGroup = reportingGroup)

    private fun rule(id: String, name: String, descriptionContains: String, categoryId: String, priority: Int) =
        AutoCategoryRule(
            id = id,
            name = name,
            descriptionContains = descriptionContains,
            amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
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
            amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
            categoryId = categoryId,
            priority = priority,
            isDefault = true,
            ruleSetId = PUBLIC_MCC_RULE_SET_ID,
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
        amountSign: AutoCategoryRuleAmountSign,
        accountKind: AssetKind?,
        vararg phrases: String,
    ): PublicMccRule = PublicMccRule(
        rule = AutoCategoryRule(
            id = id,
            name = "結構化｜$categoryId",
            amountSign = amountSign,
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

    private fun annualFeeRule(): PublicMccRule {
        val id = "public-structural-auto-credit-card-annual-fee-v2"
        val base = phraseRule(
            id,
            "expense-fees",
            AutoCategoryRuleAction.AUTO_APPLY,
            AutoCategoryRuleAmountSign.NEGATIVE,
            AssetKind.CREDIT_CARD,
            "信用卡年費",
            "年費",
            "annual fee",
        )
        return base.copy(
            conditions = base.conditions + AutoCategoryRuleCondition(
                ruleId = id,
                position = base.conditions.size,
                conditionGroup = AutoCategoryRuleConditionGroup.EXCLUDE_ANY,
                field = AutoCategoryRuleConditionField.SEARCHABLE_TEXT,
                matchMode = AutoCategoryRuleConditionMatchMode.CONTAINS,
                pattern = "減免年費",
            ),
        )
    }

    private fun genericRule(
        id: String,
        name: String,
        categoryId: String,
        amountSign: AutoCategoryRuleAmountSign,
        accountKind: AssetKind?,
        priority: Int,
        field: AutoCategoryRuleConditionField,
        matchMode: AutoCategoryRuleConditionMatchMode,
        vararg patterns: String,
    ): PublicMccRule = PublicMccRule(
        rule = AutoCategoryRule(
            id = id,
            name = name,
            amountSign = amountSign,
            categoryId = categoryId,
            priority = priority,
            isDefault = true,
            ruleSetId = PUBLIC_GENERIC_RULE_SET_ID,
            accountKind = accountKind,
            origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
            action = AutoCategoryRuleAction.AUTO_APPLY,
        ),
        conditions = patterns.mapIndexed { position, pattern ->
            AutoCategoryRuleCondition(
                ruleId = id,
                position = position,
                conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                field = if (
                    priority >= 29 &&
                    field == AutoCategoryRuleConditionField.DESCRIPTION
                ) {
                    AutoCategoryRuleConditionField.SEARCHABLE_TEXT
                } else {
                    field
                },
                matchMode = matchMode,
                pattern = pattern,
            )
        },
    )

    private fun merchantRule(
        id: String,
        name: String,
        categoryId: String,
        vararg patterns: String,
        matchMode: AutoCategoryRuleConditionMatchMode = AutoCategoryRuleConditionMatchMode.CONTAINS,
    ): PublicMccRule = merchantConditionsRule(
        id = id,
        name = name,
        categoryId = categoryId,
        conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
        patterns = patterns,
        matchMode = matchMode,
    )

    private fun merchantAllRule(
        id: String,
        name: String,
        categoryId: String,
        vararg patterns: String,
    ): PublicMccRule = merchantConditionsRule(
        id = id,
        name = name,
        categoryId = categoryId,
        conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ALL,
        patterns = patterns,
        matchMode = AutoCategoryRuleConditionMatchMode.CONTAINS,
    )

    private fun merchantConditionsRule(
        id: String,
        name: String,
        categoryId: String,
        conditionGroup: AutoCategoryRuleConditionGroup,
        patterns: Array<out String>,
        matchMode: AutoCategoryRuleConditionMatchMode,
    ): PublicMccRule = PublicMccRule(
        rule = AutoCategoryRule(
            id = id,
            name = name,
            amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
            categoryId = categoryId,
            priority = 95,
            isDefault = true,
            ruleSetId = PUBLIC_GENERIC_RULE_SET_ID,
            origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
            action = AutoCategoryRuleAction.AUTO_APPLY,
        ),
        conditions = patterns.mapIndexed { position, pattern ->
            AutoCategoryRuleCondition(
                ruleId = id,
                position = position,
                conditionGroup = conditionGroup,
                field = AutoCategoryRuleConditionField.MERCHANT_NAME,
                matchMode = matchMode,
                pattern = pattern,
            )
        },
    )

    private fun digitalWalletRule(): PublicMccRule {
        val id = "public-v4-expense-digital-wallet"
        val includePatterns = listOf("街口電支", "街口TWQR", "連加", "連支", "TAPPAY")
        val excludedKnownMerchants = listOf("便利商店", "停車大聲公", "茶之魔手", "連加*HOHO")
        return PublicMccRule(
            rule = AutoCategoryRule(
                id = id,
                name = "電子支付｜支付通路前綴",
                amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
                categoryId = "expense-digital-wallet",
                priority = 25,
                isDefault = true,
                ruleSetId = PUBLIC_GENERIC_RULE_SET_ID,
                accountKind = AssetKind.CREDIT_CARD,
                origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
                action = AutoCategoryRuleAction.AUTO_APPLY,
            ),
            conditions = buildList {
                includePatterns.forEachIndexed { position, pattern ->
                    add(
                        AutoCategoryRuleCondition(
                            ruleId = id,
                            position = position,
                            conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                            field = AutoCategoryRuleConditionField.DESCRIPTION,
                            matchMode = AutoCategoryRuleConditionMatchMode.CONTAINS,
                            pattern = pattern,
                        ),
                    )
                }
                excludedKnownMerchants.forEachIndexed { index, pattern ->
                    add(
                        AutoCategoryRuleCondition(
                            ruleId = id,
                            position = includePatterns.size + index,
                            conditionGroup = AutoCategoryRuleConditionGroup.EXCLUDE_ANY,
                            field = AutoCategoryRuleConditionField.SEARCHABLE_TEXT,
                            matchMode = AutoCategoryRuleConditionMatchMode.CONTAINS,
                            pattern = pattern,
                        ),
                    )
                }
            },
        )
    }

    private fun scopeRule(
        id: String,
        name: String,
        categoryId: String,
        amountSign: AutoCategoryRuleAmountSign,
        accountKind: AssetKind,
        priority: Int,
    ): PublicMccRule = PublicMccRule(
        rule = AutoCategoryRule(
            id = id,
            name = name,
            amountSign = amountSign,
            categoryId = categoryId,
            priority = priority,
            isDefault = true,
            ruleSetId = PUBLIC_GENERIC_RULE_SET_ID,
            accountKind = accountKind,
            origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
            action = AutoCategoryRuleAction.AUTO_APPLY,
        ),
        conditions = emptyList(),
    )

    private const val PUBLIC_MCC_RULE_SET_ID = "public-mcc-rules-v2"
    private const val PUBLIC_STRUCTURAL_RULE_SET_ID = "public-structural-rules-v2"
    const val PUBLIC_GENERIC_RULE_SET_ID = "public-generic-rules-v3"
    private const val STRUCTURAL_TEXT_FIELDS = 3
}

data class PublicMccRule(
    val rule: AutoCategoryRule,
    val conditions: List<AutoCategoryRuleCondition>,
)

internal fun publicRuleCollectionContentSha256(rules: List<PublicMccRule>): String {
    val source = AutoCategoryRuleCsvImport(
        rules = rules.map(PublicMccRule::rule),
        conditionsByRuleId = rules.associate { it.rule.id to it.conditions },
    )
    val csv = if (rules.all { it.conditions.isNotEmpty() }) {
        AutoCategoryRuleCsvCodec.encodeV2(source)
    } else {
        AutoCategoryRuleCsvCodec.encodeV4(source)
    }

    return MessageDigest.getInstance("SHA-256")
        .digest(csv.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
