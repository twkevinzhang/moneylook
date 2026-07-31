package tw.kevinzhang.core.data.db

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CategoryReportingGroup
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.TransferAnnotation

@RunWith(RobolectricTestRunner::class)
class GlobalTransferListDaoTest {
    private lateinit var database: MoneylookDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            MoneylookDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `global range includes every account safely with metadata and deterministic order`() = runBlocking {
        database.accountDao().upsertAll(
            listOf(
                account(id = "account-a", extensionId = "extension-a", extensionName = "銀行 A", accountName = "活存", currency = "TWD"),
                account(
                    id = "account-b",
                    extensionId = "extension-b",
                    extensionName = "銀行 B",
                    accountName = "信用卡",
                    currency = "USD",
                    kind = AssetKind.CREDIT_CARD,
                ),
            ),
        )
        database.transferDao().upsertAll(
            listOf(
                transfer("before", "account-a", "extension-a", "2026-06-30T23:59:59"),
                transfer("income", "account-a", "extension-a", "2026-07-01", amount = 500.0),
                transfer("same-a", "account-a", "extension-a", "2026-07-15T09:00:00"),
                transfer("same-b", "account-b", "extension-b", "2026-07-15T09:00:00"),
                transfer(
                    "cross-month-posting",
                    "account-b",
                    "extension-b",
                    "2026-07-30T10:00:00",
                    postingDateTime = "2026-08-02T09:00:00",
                ),
                transfer("unclassified", "account-b", "extension-b", "2026-07-31T23:59:59"),
                transfer("end", "account-b", "extension-b", "2026-08-01"),
            ),
        )
        database.categoryDao().upsert(Category("food", "餐飲", "#FF9800", "🍜", CategoryReportingGroup.EXPENSE))
        database.tagDao().upsert(Tag("receipt", "收據", "#4CAF50"))
        database.transferAnnotationDao().saveManualAnnotation(
            TransferAnnotation(
                transferId = "same-b",
                extensionId = "extension-b",
                categoryId = "food",
                note = "有分類",
                categoryAssignment = AssignmentSource.MANUAL,
            ),
            tagIds = setOf("receipt"),
        )

        val rows = database.transferAnnotationDao()
            .observeGlobalBetween(startInclusive = "2026-07-01", endExclusive = "2026-08-01")
            .first()

        assertEquals(
            listOf("unclassified", "cross-month-posting", "same-b", "same-a", "income"),
            rows.map { it.transfer.id },
        )
        assertEquals("信用卡", rows[0].accountName)
        assertEquals("銀行 B", rows[0].extensionName)
        assertEquals("USD", rows[0].currency)
        assertEquals(AssetKind.CREDIT_CARD, rows[0].accountKind)
        assertEquals("food", rows[2].category?.id)
        assertEquals(listOf("receipt"), rows[2].tags.map { it.id })
        assertNull(rows.first { it.transfer.id == "unclassified" }.annotation)
        val projectionFields = GlobalTransferListItem::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(
            setOf(
                "transfer", "annotation", "category", "tags", "accountName", "extensionName",
                "currency", "accountKind", "cardDisplayName", "cardMaskedPan", "cardLastFour",
            ),
            projectionFields,
        )
    }

    private fun account(
        id: String,
        extensionId: String,
        extensionName: String,
        accountName: String,
        currency: String,
        kind: AssetKind = AssetKind.DEPOSIT,
    ) = Account(
        id = id,
        extensionId = extensionId,
        extensionName = extensionName,
        accountName = accountName,
        balance = 0.0,
        currency = currency,
        lastSyncAt = 0,
        kind = kind,
    )

    private fun transfer(
        id: String,
        accountId: String,
        extensionId: String,
        txnDateTime: String,
        amount: Double = -100.0,
        postingDateTime: String? = null,
    ) = Transfer(
        id = id,
        accountId = accountId,
        extensionId = extensionId,
        txnDateTime = txnDateTime,
        description = id,
        amount = amount,
        balance = null,
        memo = "",
        postingDateTime = postingDateTime,
    )
}
