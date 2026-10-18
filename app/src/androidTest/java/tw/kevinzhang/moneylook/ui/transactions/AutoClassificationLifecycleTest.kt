package tw.kevinzhang.moneylook.ui.transactions

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tw.kevinzhang.core.data.db.*
import tw.kevinzhang.core.data.model.*
import tw.kevinzhang.moneylook.ai.*
import tw.kevinzhang.moneylook.schedule.ClassificationTraceMaintenanceScheduler
import tw.kevinzhang.moneylook.sync.AutoCategorizer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class AutoClassificationLifecycleTest {
    @Test fun stoppedBatchRecreatesResumesOnlyRemainingAndRefreshesSettings() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefix = "classification-lifecycle-test-${UUID.randomUUID()}-"
        // All settings and seeding preferences are isolated from the user's App preferences.
        val preferenceNames = mutableSetOf<String>()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                synchronized(preferenceNames) { preferenceNames.add(prefix + name) }
                return super.getSharedPreferences(prefix + name, mode)
            }
        }
        val db = Room.inMemoryDatabaseBuilder(context, MoneylookDatabase::class.java).allowMainThreadQueries().build()
        val lifecycle = ViewModelStore()
        val calls = CopyOnWriteArrayList<String>()
        val blocked = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val classifier = AiClassifier { input ->
            calls.add(input.summary)
            if (calls.size == 2) {
                blocked.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            AiCategoryProposal(AiCategorySelection.New("測試分類", CategoryReportingGroup.EXPENSE), "合成測試")
        }
        val settingsStore = AiSettingsStore(context, AiSecretProtector())
        val session = AutoClassificationSessionStore(context)
        val local = LocalClassificationStore(db)
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun createViewModel(): ClassificationViewModel {
            val categorizer = AutoCategorizer(local, db.transferDao(), db.transferAnnotationDao(), db.autoCategoryRuleDao(), db.categoryDao(),
                RoomClassificationTraceStore(db, db.transferAnnotationDao(), db.ingestionProvenanceDao()),
                RoomClassificationCatalogResetStore(db, db.transferAnnotationDao(), db.ingestionProvenanceDao()),
                classificationTraceMaintenanceScheduler = object : ClassificationTraceMaintenanceScheduler {
                    override fun wake() {}
                    override fun continueAfter(continuation: Int) {}
                }, aiClassifier = classifier, aiStore = AiClassificationStore(db))
            lateinit var vm: ClassificationViewModel
            onMain {
                vm = ClassificationViewModel(context, SavedStateHandle(), db.accountDao(), db.creditCardInstrumentDao(),
                    db.categoryDao(), db.tagDao(), db.transferAnnotationDao(), db.autoCategoryRuleDao(),
                    TransactionDetailDraftStore(db, db.tagDao(), db.transferAnnotationDao(), db.autoCategoryRuleDao()),
                    categorizer, db.ingestionProvenanceDao(), local, settingsStore, AiClassificationStore(db), session)
                lifecycle.put("classification", vm)
                vm.refreshAiConfiguration()
            }
            return vm
        }
        try {
            db.accountDao().upsertAll(listOf(Account("a", "e", "Bank", "Account", 0.0, "TWD", 0)))
            db.categoryDao().upsert(Category("old", "舊分類", "#607D8B"))
            db.aiClassificationDao().putState(AiClassificationState())
            listOf("one", "two").forEach { id ->
                db.transferDao().upsertAll(listOf(Transfer(id, "a", "e", "2026-10-18", "合成交易$id", -60.0, null, "")))
                db.transferAnnotationDao().upsert(TransferAnnotation(id, "e", "old", autoRuleId = "removed-default"))
                db.openHelper.writableDatabase.execSQL("INSERT INTO ai_classification_work VALUES(?,'old','removed-default',NULL,0)", arrayOf(id))
            }
            val settings = AiSettings(AiProvider.CUSTOM, "test-model", "https://synthetic.invalid/v1", "synthetic-token")
            settingsStore.save(settings)
            val vm = createViewModel()
            withTimeout(10_000) { vm.autoClassificationOverview.first { it.action == AutoClassificationAction.START } }
            onMain { vm.showApplyAllRulesConfirmation() }
            withTimeout(10_000) { vm.applyAllRulesUiState.first { it == ApplyAllRulesUiState.Confirming } }
            assertEquals(0, calls.size)
            onMain { vm.startApplyAllRules() }
            withTimeout(10_000) { blocked.await() }
            assertTrue(session.hasUnfinishedSession())
            onMain { vm.stopApplyAllRules() }
            withTimeout(10_000) { cancelled.await() }
            assertTrue(vm.applyAllRulesUiState.value is ApplyAllRulesUiState.Error)
            assertFalse(db.aiClassificationDao().state()!!.complete)
            onMain { lifecycle.clear() }

            val resumed = createViewModel()
            val overview = withTimeout(10_000) { resumed.autoClassificationOverview.first { it.action == AutoClassificationAction.RESUME } }
            assertEquals(1, overview.preview!!.pendingTransferCount)
            onMain { resumed.showApplyAllRulesConfirmation() }
            withTimeout(10_000) { resumed.applyAllRulesUiState.first { it == ApplyAllRulesUiState.Confirming } }
            assertEquals(2, calls.size)
            onMain { resumed.startApplyAllRules() }
            withTimeout(10_000) { resumed.applyAllRulesUiState.first { it is ApplyAllRulesUiState.Success } }
            assertEquals(3, calls.size)
            assertEquals(1, calls.count { "one" in it })
            assertEquals(2, calls.count { "two" in it })
            assertFalse(session.hasUnfinishedSession())
            assertTrue(db.aiClassificationDao().state()!!.complete)
            onMain { resumed.dismissApplyAllRules() }
            withTimeout(10_000) { resumed.autoClassificationOverview.first { it.action == AutoClassificationAction.EMPTY } }

            settingsStore.save(settings.copy(model = "updated-model"))
            onMain { resumed.refreshAiConfiguration() }
            val updated = withTimeout(10_000) { resumed.autoClassificationOverview.first {
                (it.configuration as? AiConfigurationSummary.Configured)?.model == "updated-model"
            } }
            assertEquals(AutoClassificationAction.EMPTY, updated.action)
            context.getSharedPreferences("ai_classification_settings", Context.MODE_PRIVATE).edit().putString("encrypted", "invalid-ciphertext").commit()
            onMain { resumed.refreshAiConfiguration() }
            withTimeout(10_000) { resumed.autoClassificationOverview.first {
                it.configuration == AiConfigurationSummary.CredentialsUnavailable && it.action == AutoClassificationAction.OPEN_SETTINGS
            } }
            assertEquals(3, calls.size)
        } finally {
            onMain { lifecycle.clear() }
            db.close()
            synchronized(preferenceNames) { preferenceNames.forEach { instrumentation.targetContext.deleteSharedPreferences(it) } }
        }
    }
}
