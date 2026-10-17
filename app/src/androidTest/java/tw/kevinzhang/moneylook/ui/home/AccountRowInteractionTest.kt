package tw.kevinzhang.moneylook.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.moneylook.ui.theme.MoneylookTheme

@RunWith(AndroidJUnit4::class)
class AccountRowInteractionTest {
    @get:Rule val composeRule = createComposeRule()

    private fun account(id: String, number: String) = Account(
        id, "fictional-bank", "測試銀行", "台幣活期存款", 100.0, "TWD", 0, accountNo = number,
    )

    @Test fun sameNamedAccountsShowTheirOwnFullNumbersAndOpenTheirOwnLedger() {
        val accounts = listOf(account("a", "001234567890"), account("b", "001234567891"))
        var selected: String? = null
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                Column {
                    accounts.forEach { item -> AccountRow(item, 0) { selected = item.id } }
                }
            }
        }
        composeRule.onNodeWithText("帳號 001234567890", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText("帳號 001234567891", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("home-account-b").performClick()
        assertEquals("b", selected)
        composeRule.onNodeWithTag("home-account-a").performClick()
        assertEquals("a", selected)
    }

    @Test fun narrowWindowWithLargeTextPlacesAmountBelowTheFullNumber() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MoneylookTheme(darkTheme = false, dynamicColor = false) {
                    Column(Modifier.width(280.dp)) { AccountRow(account("a", "0012345678901234"), 0) {} }
                }
            }
        }
        val number = composeRule.onNodeWithText("帳號 0012345678901234", useUnmergedTree = true)
        number.assertIsDisplayed()
        val numberBounds = number.getUnclippedBoundsInRoot()
        val rowBounds = composeRule.onNodeWithTag("home-account-a").getUnclippedBoundsInRoot()
        val amountBounds = composeRule.onNodeWithText("$ 100", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(numberBounds.left >= rowBounds.left && numberBounds.right <= rowBounds.right)
        assertTrue(amountBounds.top >= numberBounds.bottom)
    }
}
