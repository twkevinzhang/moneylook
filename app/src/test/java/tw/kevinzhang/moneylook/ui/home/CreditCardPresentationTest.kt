package tw.kevinzhang.moneylook.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.kevinzhang.core.data.db.CreditCardInstrumentMetadata

class CreditCardPresentationTest {
    @Test
    fun `metadata has no full PAN representation and uses the bank mask`() {
        val card = CreditCardDisplay(
            id = "opaque-card-id",
            displayName = "測試御璽卡",
            maskedPan = "4111-••••-••••-1111",
            lastFour = "1111",
            network = "Visa",
            productType = "御璽卡",
            holderRole = "supplementary",
            holderName = "測試持卡人",
            status = "正常",
            expiryMonth = 7,
            expiryYear = 2030,
            creditLimit = null,
            availableCredit = null,
            canRevealPan = false,
        )

        assertEquals("測試御璽卡", card.title())
        assertEquals("4111-••••-••••-1111", card.numberLabel())
        assertEquals("Visa · 御璽卡 · 附卡 · 正常", card.metadataLabel())
        assertEquals("有效期限 2030/07", card.expiryLabel())
    }

    @Test
    fun `last four is the only fallback and invalid metadata is omitted`() {
        val card = CreditCardDisplay(
            id = "opaque-card-id",
            displayName = " ",
            maskedPan = " ",
            lastFour = "4242",
            network = null,
            productType = null,
            holderRole = null,
            holderName = null,
            status = null,
            expiryMonth = 14,
            expiryYear = 2030,
            creditLimit = null,
            availableCredit = null,
            canRevealPan = false,
        )

        assertEquals("信用卡", card.title())
        assertEquals("•••• 4242", card.numberLabel())
        assertNull(card.metadataLabel())
        assertNull(card.expiryLabel())
        assertEquals("2 張卡", creditCardCountLabel(2))
        assertNull(creditCardCountLabel(0))
    }

    @Test
    fun `Room card maps only safe metadata into the Compose display model`() {
        val display = CreditCardInstrumentMetadata(
            id = "opaque-card-id",
            maskedPan = "•••• 4242",
            lastFour = "4242",
            displayName = "虛構測試卡",
            network = null,
            productType = null,
            holderRole = null,
            holderName = null,
            status = null,
            expiryMonth = null,
            expiryYear = null,
            creditLimit = null,
            availableCredit = null,
            canRevealPan = true,
        ).toDisplay()

        assertEquals("•••• 4242", display.numberLabel())
        assertEquals("虛構測試卡", display.title())
        assertEquals(true, display.canRevealPan)
    }
}
