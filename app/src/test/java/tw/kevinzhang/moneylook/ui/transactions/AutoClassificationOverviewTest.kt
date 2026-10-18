package tw.kevinzhang.moneylook.ui.transactions

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.model.AiClassificationPreview
import tw.kevinzhang.moneylook.ai.*

@RunWith(RobolectricTestRunner::class)
class AutoClassificationOverviewTest {
    private val configured = AiConfigurationSummary.Configured("自訂服務", "gpt-6-luna")
    private val pending = AiClassificationPreview(2, true, false)

    @Test fun actionDependsOnSettingsPendingAndSessionRatherThanRules() {
        data class Case(val settings: AiConfigurationSummary, val preview: AiClassificationPreview?,
            val unfinished: Boolean, val busy: Boolean, val expected: AutoClassificationAction)
        val cases = listOf(
            Case(configured, pending, false, true, AutoClassificationAction.BUSY),
            Case(AiConfigurationSummary.Loading, pending, false, false, AutoClassificationAction.LOADING),
            Case(configured, null, false, false, AutoClassificationAction.LOADING),
            Case(AiConfigurationSummary.Missing, pending, false, false, AutoClassificationAction.OPEN_SETTINGS),
            Case(AiConfigurationSummary.CredentialsUnavailable, pending, true, false, AutoClassificationAction.OPEN_SETTINGS),
            Case(configured, pending.copy(pendingTransferCount = 0), true, false, AutoClassificationAction.EMPTY),
            Case(configured, pending, false, false, AutoClassificationAction.START),
            Case(configured, pending, true, false, AutoClassificationAction.RESUME),
            Case(configured, pending.copy(upgradeStarted = true), false, false, AutoClassificationAction.RESUME),
            Case(configured, pending.copy(firstUpgradePending = false, upgradeStarted = true), false, false, AutoClassificationAction.START),
        )
        cases.forEach { case ->
            assertEquals(case.toString(), case.expected, resolveAutoClassificationAction(case.settings, case.preview, case.unfinished, case.busy))
        }
    }

    @Test fun configurationSummaryContainsOnlyProviderAndModel() {
        val settings = AiSettings(AiProvider.CUSTOM, "gpt-6-luna", "https://private.example/v1", "secret-token", "access-id", "access-secret")
        val summary = AiSettingsLoadResult.Ready(settings).toConfigurationSummary()
        assertEquals(configured, summary)
        listOf("secret-token", "access-id", "access-secret", "private.example").forEach { assertFalse(summary.toString().contains(it)) }
        assertEquals(AiConfigurationSummary.Missing, AiSettingsLoadResult.Missing.toConfigurationSummary())
        assertEquals(AiConfigurationSummary.CredentialsUnavailable, AiSettingsLoadResult.CredentialsUnavailable.toConfigurationSummary())
    }

    @Test fun unfinishedSessionSurvivesStoreRecreationAndCanBeCleared() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = AutoClassificationSessionStore(context)
        store.setUnfinishedSession(false)
        assertFalse(store.hasUnfinishedSession())
        store.setUnfinishedSession(true)
        assertTrue(AutoClassificationSessionStore(context).hasUnfinishedSession())
        store.setUnfinishedSession(false)
        assertFalse(AutoClassificationSessionStore(context).hasUnfinishedSession())
    }
}
