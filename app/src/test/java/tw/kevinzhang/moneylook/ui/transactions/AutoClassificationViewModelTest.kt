package tw.kevinzhang.moneylook.ui.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.db.*
import tw.kevinzhang.core.data.model.*
import tw.kevinzhang.moneylook.ai.*
import tw.kevinzhang.moneylook.schedule.ClassificationTraceMaintenanceScheduler
import tw.kevinzhang.moneylook.sync.AutoCategorizer

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AutoClassificationViewModelTest {
    private suspend fun withFixture(brokenSettings: Boolean = false, test: suspend (ClassificationViewModel, MoneylookDatabase, AutoClassificationSessionStore) -> Unit) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context = object : android.content.ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val preferences = super.getSharedPreferences(name, mode)
                if (!brokenSettings || name != "ai_classification_settings") return preferences
                return object : android.content.SharedPreferences by preferences {
                    override fun getString(key: String?, defValue: String?): String? = error("Settings storage unavailable")
                }
            }
        }
        val db = Room.inMemoryDatabaseBuilder(context, MoneylookDatabase::class.java).allowMainThreadQueries().build()
        val lifecycle = ViewModelStore()
        try {
            db.accountDao().upsertAll(listOf(Account("a", "e", "Bank", "Account", 0.0, "TWD", 0)))
            // A completed upgrade lets the persistent daily-session hint be tested independently.
            db.aiClassificationDao().putState(AiClassificationState(started = true, complete = true))
            val local = LocalClassificationStore(db)
            val session = AutoClassificationSessionStore(context)
            session.setUnfinishedSession(true)
            val categorizer = AutoCategorizer(local, db.transferDao(), db.transferAnnotationDao(), db.autoCategoryRuleDao(), db.categoryDao(),
                RoomClassificationTraceStore(db, db.transferAnnotationDao(), db.ingestionProvenanceDao()),
                RoomClassificationCatalogResetStore(db, db.transferAnnotationDao(), db.ingestionProvenanceDao()),
                classificationTraceMaintenanceScheduler = object : ClassificationTraceMaintenanceScheduler {
                    override fun wake() {}
                    override fun continueAfter(continuation: Int) {}
                }, aiClassifier = AiClassifier { error("No classification request is allowed in this test") }, aiStore = AiClassificationStore(db))
            val vm = ClassificationViewModel(context, SavedStateHandle(), db.accountDao(), db.creditCardInstrumentDao(),
                db.categoryDao(), db.tagDao(), db.transferAnnotationDao(), db.autoCategoryRuleDao(),
                TransactionDetailDraftStore(db, db.tagDao(), db.transferAnnotationDao(), db.autoCategoryRuleDao()),
                categorizer, db.ingestionProvenanceDao(), local, AiSettingsStore(context, AiSecretProtector()), AiClassificationStore(db), session)
            lifecycle.put("classification", vm)
            test(vm, db, session)
        } finally {
            lifecycle.clear()
            db.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun zeroPendingDuringDialogDoesNotMakeFutureWorkAResume() = runBlocking {
        withFixture(brokenSettings = true) { vm, db, session ->
            vm.showApplyAllRulesConfirmation()
            withTimeout(10_000) { vm.applyAllRulesUiState.first { it is ApplyAllRulesUiState.Error } }
            withTimeout(10_000) { vm.autoClassificationOverview.first { it.preview != null } }
            assertFalse("Zero canonical work must clear the unfinished hint even with a dialog open", session.hasUnfinishedSession())
            vm.dismissApplyAllRules()
            db.transferDao().upsertAll(listOf(Transfer("later", "a", "e", "2026-10-18", "咖啡", -60.0, null, "")))
            val next = withTimeout(10_000) { vm.autoClassificationOverview.first { it.preview?.pendingTransferCount == 1 } }
            assertFalse(next.resuming)
            assertTrue(db.aiClassificationDao().state()!!.complete)
        }
    }

    @Test fun missingSettingsCannotStartUpgradeOrClassification() = runBlocking {
        withFixture { vm, db, session ->
            session.setUnfinishedSession(false)
            vm.showApplyAllRulesConfirmation()
            withTimeout(10_000) { vm.applyAllRulesUiState.first { it == ApplyAllRulesUiState.Idle } }
            vm.startApplyAllRules()
            assertEquals(ApplyAllRulesUiState.Idle, vm.applyAllRulesUiState.value)
            assertTrue(db.aiClassificationDao().state()!!.complete)
        }
    }
}
