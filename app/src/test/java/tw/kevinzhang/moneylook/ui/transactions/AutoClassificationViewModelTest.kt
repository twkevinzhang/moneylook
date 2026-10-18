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
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import tw.kevinzhang.core.data.db.*
import tw.kevinzhang.core.data.model.*
import tw.kevinzhang.moneylook.ai.*
import tw.kevinzhang.moneylook.schedule.ClassificationTraceMaintenanceScheduler
import tw.kevinzhang.moneylook.schedule.BackgroundClassificationScheduler
import tw.kevinzhang.moneylook.sync.AutoCategorizer

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ClassificationTestSecretProtectorShadow::class], instrumentedPackages = ["tw.kevinzhang.moneylook.ai"])
class AutoClassificationViewModelTest {
    private class TestScheduler : BackgroundClassificationScheduler {
        var starts = 0
        var stops = 0
        var reconciles = 0
        val reconciled = CompletableDeferred<Unit>()
        override suspend fun start(): Boolean { starts++; return true }
        override suspend fun stop() { stops++ }
        override suspend fun reconcile() { reconciles++; reconciled.complete(Unit) }
    }

    private suspend fun withFixture(brokenSettings: Boolean = false, configured: Boolean = false,
        test: suspend (ClassificationViewModel, MoneylookDatabase, AutoClassificationSessionStore, TestScheduler) -> Unit) {
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
        val db = Room.inMemoryDatabaseBuilder(context, MoneylookDatabase::class.java)
            .addCallback(MoneylookDatabase.defaultClassificationSeedCallback()).allowMainThreadQueries().build()
        val lifecycle = ViewModelStore()
        try {
            db.accountDao().upsertAll(listOf(Account("a", "e", "Bank", "Account", 0.0, "TWD", 0)))
            // A completed upgrade lets the persistent daily-session hint be tested independently.
            db.aiClassificationDao().putState(AiClassificationState(started = true, complete = true))
            val local = LocalClassificationStore(db)
            val session = AutoClassificationSessionStore(context)
            session.setUnfinishedSession(true)
            val settings = AiSettingsStore(context, AiSecretProtector())
            if (configured) settings.save(AiSettings(AiProvider.CUSTOM, "test-model", "https://synthetic.invalid/v1", "synthetic-token"))
            val scheduler = TestScheduler()
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
                categorizer, db.ingestionProvenanceDao(), local, settings, AiClassificationStore(db), session,
                ClassificationJobStore(db), scheduler)
            lifecycle.put("classification", vm)
            test(vm, db, session, scheduler)
        } finally {
            lifecycle.clear()
            db.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun zeroPendingDuringDialogDoesNotMakeFutureWorkAResume() = runBlocking {
        withFixture(brokenSettings = true) { vm, db, session, _ ->
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
        withFixture { vm, db, session, _ ->
            session.setUnfinishedSession(false)
            vm.showApplyAllRulesConfirmation()
            withTimeout(10_000) { vm.applyAllRulesUiState.first { it == ApplyAllRulesUiState.Idle } }
            vm.startApplyAllRules()
            assertEquals(ApplyAllRulesUiState.Idle, vm.applyAllRulesUiState.value)
            assertTrue(db.aiClassificationDao().state()!!.complete)
        }
    }

    @Test fun confirmedStartOnlySchedulesOnceAndNeverRunsForegroundClassification() = runBlocking {
        withFixture(configured = true) { vm, db, _, scheduler ->
            db.transferDao().upsertAll(listOf(Transfer("pending", "a", "e", "2026-10-18", "咖啡", -60.0, null, "")))
            vm.showApplyAllRulesConfirmation()
            withTimeout(10_000) { vm.applyAllRulesUiState.first { it == ApplyAllRulesUiState.Confirming } }
            assertEquals(0, scheduler.starts)
            vm.startApplyAllRules()
            vm.startApplyAllRules()
            withTimeout(10_000) { vm.applyAllRulesUiState.first { it == ApplyAllRulesUiState.Idle } }
            assertEquals(1, scheduler.starts)
            assertNull(db.transferAnnotationDao().observeDetail("pending").first()!!.annotation)
            vm.stopApplyAllRules()
            assertEquals(1, scheduler.stops)
        }
    }

    @Test fun refreshReconcilesWithoutAutomaticallyStartingAnotherSession() = runBlocking {
        withFixture { vm, _, _, scheduler ->
            vm.refreshAiConfiguration()
            withTimeout(10_000) { vm.autoClassificationOverview.first { it.configuration !is AiConfigurationSummary.Loading } }
            assertEquals(0, scheduler.starts)
            assertEquals(1, scheduler.reconciles)
        }
    }

    @Test fun editingRuleWhileRunningKeepsEditingAllowedAndImmediatelyBlocksManualStart() = runBlocking {
        withFixture(configured = true) { vm, db, _, scheduler ->
            db.transferDao().upsertAll(listOf(Transfer("pending", "a", "e", "2026-10-18", "咖啡", -60.0, null, "")))
            val jobs = ClassificationJobStore(db)
            val queued = jobs.enqueue()!!
            jobs.prepare(queued.attemptId!!)
            withTimeout(10_000) { vm.autoClassificationOverview.first { it.action == AutoClassificationAction.BACKGROUND } }
            vm.saveRule(AutoRuleDraft(id = "custom", name = "自訂規則", descriptionContains = "咖啡",
                categoryId = db.categoryDao().observeAll().first().first().id))
            vm.showApplyAllRulesConfirmation()
            assertEquals(ApplyAllRulesUiState.Idle, vm.applyAllRulesUiState.value)
            withTimeout(10_000) { db.autoCategoryRuleDao().observeAll().first { rules -> rules.any { it.rule.id == "custom" } } }
            val updated = withTimeout(10_000) { vm.autoClassificationOverview.first {
                (it.background?.generation ?: 0L) > queued.generation
            } }
            assertEquals(AutoClassificationAction.BACKGROUND, updated.action)
            assertTrue(updated.background!!.fullReclassification)
            assertEquals(0, scheduler.starts)
            withTimeout(10_000) { scheduler.reconciled.await() }
            assertTrue(scheduler.reconciles > 0)
        }
    }

    @Test fun resumeConfirmationPreviewsEntireNonmanualLedgerAfterRuleChange() = runBlocking {
        withFixture(configured = true) { vm, db, _, _ ->
            val category = db.categoryDao().observeAll().first().first()
            db.transferDao().upsertAll(listOf(
                Transfer("historic", "a", "e", "2020-01-01", "已分類交易", -60.0, null, ""),
                Transfer("manual", "a", "e", "2020-01-01", "人工分類交易", -80.0, null, "")))
            db.transferAnnotationDao().upsert(TransferAnnotation("historic", "e", category.id))
            db.transferAnnotationDao().upsert(TransferAnnotation("manual", "e", category.id,
                note = "保留備註", categoryAssignment = AssignmentSource.MANUAL))
            db.autoCategoryRuleDao().upsertWithDetails(AutoCategoryRule(id = "changed", name = "規則變更",
                descriptionContains = "已分類", categoryId = category.id), emptyList(), emptySet())
            val jobs = ClassificationJobStore(db)
            vm.refreshAiConfiguration()
            withTimeout(10_000) { vm.autoClassificationOverview.first { it.action == AutoClassificationAction.RESUME } }
            vm.showApplyAllRulesConfirmation()
            withTimeout(10_000) { vm.applyAllRulesUiState.first { it == ApplyAllRulesUiState.Confirming } }
            assertEquals(1, jobs.preview().pendingTransferCount)
            assertTrue(vm.applyScopeText.value.contains("整個帳本所有非人工分類交易"))
            assertTrue(vm.applyScopeText.value.contains("共 1 筆"))
            assertTrue(vm.applyScopeText.value.contains("100 次 AI 請求、2000 筆交易或 40 分鐘"))
            assertEquals("保留備註", db.transferAnnotationDao().observeDetail("manual").first()!!.annotation!!.note)
        }
    }
}

/** Test-only settings persistence: all values are synthetic; AndroidKeyStore requires a device. */
@Implements(value = AiSecretProtector::class, isInAndroidSdk = false)
class ClassificationTestSecretProtectorShadow {
    @Implementation fun encrypt(value: String): String = value
    @Implementation fun decrypt(value: String): String = value
}
