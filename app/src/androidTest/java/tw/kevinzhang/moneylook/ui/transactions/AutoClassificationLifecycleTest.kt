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
import tw.kevinzhang.moneylook.schedule.BackgroundClassificationScheduler
import tw.kevinzhang.moneylook.sync.AutoCategorizer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class AutoClassificationLifecycleTest {
    @Test fun backgroundJobSurvivesViewModelRecreationAndOnlyExplicitStopPausesIt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val prefix = "classification-lifecycle-test-${UUID.randomUUID()}-"
        val preferenceNames = mutableSetOf<String>()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                synchronized(preferenceNames) { preferenceNames.add(prefix + name) }
                return super.getSharedPreferences(prefix + name, mode)
            }
        }
        val db = Room.inMemoryDatabaseBuilder(context, MoneylookDatabase::class.java).allowMainThreadQueries().build()
        val lifecycle = ViewModelStore()
        val settingsStore = AiSettingsStore(context, AiSecretProtector())
        val session = AutoClassificationSessionStore(context)
        val local = LocalClassificationStore(db)
        val jobs = ClassificationJobStore(db)
        var starts = 0
        var stops = 0
        val scheduler = object : BackgroundClassificationScheduler {
            override suspend fun start(): Boolean {
                starts++
                return jobs.enqueue() != null
            }
            override suspend fun stop() {
                stops++
                jobs.current().attemptId?.let { jobs.pause(it, "已停止並保存進度") }
            }
            override suspend fun reconcile() {}
        }
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun createViewModel(): ClassificationViewModel {
            val categorizer = AutoCategorizer(local, db.transferDao(), db.transferAnnotationDao(), db.autoCategoryRuleDao(), db.categoryDao(),
                RoomClassificationTraceStore(db, db.transferAnnotationDao(), db.ingestionProvenanceDao()),
                RoomClassificationCatalogResetStore(db, db.transferAnnotationDao(), db.ingestionProvenanceDao()),
                classificationTraceMaintenanceScheduler = object : ClassificationTraceMaintenanceScheduler {
                    override fun wake() {}
                    override fun continueAfter(continuation: Int) {}
                }, aiClassifier = AiClassifier { error("The page must never classify transactions") }, aiStore = AiClassificationStore(db))
            lateinit var vm: ClassificationViewModel
            onMain {
                vm = ClassificationViewModel(context, SavedStateHandle(), db.accountDao(), db.creditCardInstrumentDao(),
                    db.categoryDao(), db.tagDao(), db.transferAnnotationDao(), db.autoCategoryRuleDao(),
                    TransactionDetailDraftStore(db, db.tagDao(), db.transferAnnotationDao(), db.autoCategoryRuleDao()),
                    categorizer, db.ingestionProvenanceDao(), local, settingsStore, AiClassificationStore(db), session, jobs, scheduler)
                lifecycle.put("classification", vm)
                vm.refreshAiConfiguration()
            }
            return vm
        }
        try {
            db.accountDao().upsertAll(listOf(Account("a", "e", "Bank", "Account", 0.0, "TWD", 0)))
            db.aiClassificationDao().putState(AiClassificationState(started = true, complete = true))
            db.transferDao().upsertAll(listOf(Transfer("pending", "a", "e", "2026-10-18", "合成交易", -60.0, null, "")))
            val settings = AiSettings(AiProvider.CUSTOM, "test-model", "https://synthetic.invalid/v1", "synthetic-token")
            settingsStore.save(settings)
            val vm = createViewModel()
            withTimeout(10_000) { vm.autoClassificationOverview.first { it.action == AutoClassificationAction.START } }
            onMain { vm.showApplyAllRulesConfirmation() }
            withTimeout(10_000) { vm.applyAllRulesUiState.first { it == ApplyAllRulesUiState.Confirming } }
            assertEquals(0, starts)
            onMain { vm.startApplyAllRules(); vm.startApplyAllRules() }
            withTimeout(10_000) { vm.autoClassificationOverview.first { it.action == AutoClassificationAction.BACKGROUND } }
            assertEquals(1, starts)
            assertEquals(ApplyAllRulesUiState.Idle, vm.applyAllRulesUiState.value)
            val queued = jobs.current()
            jobs.prepare(queued.attemptId!!)
            onMain { lifecycle.clear() }
            assertEquals("RUNNING", jobs.current().status)
            assertEquals(0, stops)

            val resumed = createViewModel()
            withTimeout(10_000) { resumed.autoClassificationOverview.first { it.action == AutoClassificationAction.BACKGROUND } }
            onMain { resumed.showApplyAllRulesConfirmation() }
            assertEquals(ApplyAllRulesUiState.Idle, resumed.applyAllRulesUiState.value)
            assertEquals(1, starts)
            onMain { resumed.stopApplyAllRules() }
            withTimeout(10_000) { resumed.autoClassificationOverview.first { it.action == AutoClassificationAction.RESUME } }
            assertEquals(1, stops)
            onMain { resumed.showApplyAllRulesConfirmation() }
            withTimeout(10_000) { resumed.applyAllRulesUiState.first { it == ApplyAllRulesUiState.Confirming } }
            assertEquals(1, starts)
            onMain { resumed.cancelApplyAllRules() }
            settingsStore.save(settings.copy(model = "updated-model"))
            onMain { resumed.refreshAiConfiguration() }
            withTimeout(10_000) { resumed.autoClassificationOverview.first {
                (it.configuration as? AiConfigurationSummary.Configured)?.model == "updated-model"
            } }
            assertEquals(1, starts)
            assertNull(db.transferAnnotationDao().observeDetail("pending").first()!!.annotation)
        } finally {
            onMain { lifecycle.clear() }
            db.close()
            synchronized(preferenceNames) { preferenceNames.forEach { instrumentation.targetContext.deleteSharedPreferences(it) } }
        }
    }
}
