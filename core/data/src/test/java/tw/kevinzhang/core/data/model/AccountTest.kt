package tw.kevinzhang.core.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountTest {
    @Test
    fun `id is composed of extensionId and accountName`() {
        val account = Account(
            id = "tw.bot_活期存款",
            extensionId = "tw.bot",
            extensionName = "台灣銀行",
            accountName = "活期存款",
            balance = 12345.67,
            currency = "TWD",
            lastSyncAt = 1000L,
        )
        assertEquals("tw.bot", account.extensionId)
        assertEquals("活期存款", account.accountName)
        assertEquals("tw.bot_活期存款", account.id)
    }
}
