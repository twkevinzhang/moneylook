package tw.kevinzhang.moneylook.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.model.AssetKind

class AccountPresentationTest {
    @Test
    fun depositShowsItsBalanceAsAnAsset() {
        val presentation = accountRowPresentation(
            kind = AssetKind.DEPOSIT,
            balance = 1_496.0,
            currency = "TWD",
            availableCredit = null,
        )

        assertEquals("$ 1,496", presentation.primaryAmount)
        assertNull(presentation.supportingText)
        assertFalse(presentation.isLiability)
    }

    @Test
    fun creditCardShowsPositiveBalanceAsLiabilityAndAvailableCredit() {
        val presentation = accountRowPresentation(
            kind = AssetKind.CREDIT_CARD,
            balance = 7_498.0,
            currency = "TWD",
            availableCredit = 22_502.0,
        )

        assertEquals("-$ 7,498", presentation.primaryAmount)
        assertEquals("可用額度 $ 22,502", presentation.supportingText)
        assertTrue(presentation.isLiability)
    }

    @Test
    fun loanUsesLiabilityFormatWithoutCreditAvailabilityText() {
        val presentation = accountRowPresentation(
            kind = AssetKind.LOAN,
            balance = 1234.5,
            currency = "USD",
            availableCredit = 999.0,
        )

        assertEquals("-USD 1234.50", presentation.primaryAmount)
        assertNull(presentation.supportingText)
        assertTrue(presentation.isLiability)
    }

    @Test
    fun zeroLiabilityDoesNotRenderANegativeZero() {
        val presentation = accountRowPresentation(
            kind = AssetKind.CREDIT_CARD,
            balance = 0.0,
            currency = "TWD",
            availableCredit = null,
        )

        assertEquals("$ 0", presentation.primaryAmount)
    }
}
