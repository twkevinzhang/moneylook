package tw.kevinzhang.moneylook.ui.home

import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.db.TransferDao
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.extension_runtime.ExtensionRunner
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.session.SessionStore
import tw.kevinzhang.moneylook.schedule.ScheduleStatus
import tw.kevinzhang.moneylook.schedule.ScheduleWorker
import tw.kevinzhang.moneylook.ui.login.LoginWebViewActivity
import java.time.ZonedDateTime
import javax.inject.Inject

enum class SyncState { IDLE, SYNCING, SUCCESS, ERROR }

data class ExtensionSyncStatus(
    val extension: InstalledExtension,
    val syncState: SyncState = SyncState.IDLE,
    val errorMessage: String? = null,
    val hasSession: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installedExtensionDao: InstalledExtensionDao,
    private val accountDao: AccountDao,
    private val transferDao: TransferDao,
    private val extensionRunner: ExtensionRunner,
    private val sessionStore: SessionStore,
    private val gson: Gson,
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)
    private val cronParser = CronParser(
        CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING)
    )

    val accounts = accountDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val extensions = installedExtensionDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncStatuses = MutableStateFlow<Map<String, ExtensionSyncStatus>>(emptyMap())

    val syncStatuses: StateFlow<Map<String, ExtensionSyncStatus>> =
        combine(
            _syncStatuses,
            sessionStore.sessionIds,
            installedExtensionDao.observeAll(),
        ) { mutable, sessionIds, exts ->
            exts.associate { ext ->
                val current = mutable[ext.id]
                ext.id to ExtensionSyncStatus(
                    extension = ext,
                    syncState = current?.syncState ?: SyncState.IDLE,
                    errorMessage = current?.errorMessage,
                    hasSession = ext.id in sessionIds,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private fun tickerFlow(intervalMs: Long) = flow {
        while (true) {
            emit(Unit)
            delay(intervalMs)
        }
    }

    /** Per-extension WorkManager schedule status, keyed by extensionId. */
    val scheduleStatuses: StateFlow<Map<String, ScheduleStatus>> =
        installedExtensionDao.observeAll()
            .flatMapLatest { extensions ->
                val withSchedule = extensions.filter { it.scheduleCachePath != null }
                if (withSchedule.isEmpty()) return@flatMapLatest flowOf(emptyMap())
                val perExtFlows = withSchedule.map { ext ->
                    workManager.getWorkInfosByTagFlow(ScheduleWorker.tag(ext.id))
                        .map { workInfos ->
                            val isActive = workInfos.any {
                                it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                            }
                            val status: ScheduleStatus = if (isActive) {
                                ScheduleStatus.Active(nextExecMs(ext.scheduleCron!!))
                            } else {
                                ScheduleStatus.Disabled
                            }
                            ext.id to status
                        }
                }
                combine(perExtFlows) { pairs -> pairs.associate { it } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Per-extension countdown in ms, keyed by extensionId. Only meaningful when scheduleStatus is Active. */
    val countdownMs: StateFlow<Map<String, Long>> =
        combine(scheduleStatuses, tickerFlow(1_000L)) { statuses, _ ->
            statuses.mapValues { (_, status) ->
                when (status) {
                    is ScheduleStatus.Active -> (status.nextExecMs - System.currentTimeMillis()).coerceAtLeast(0L)
                    else -> 0L
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun sync(extension: InstalledExtension) {
        viewModelScope.launch {
            updateStatus(extension.id) { it.copy(syncState = SyncState.SYNCING, errorMessage = null) }
            val result = try {
                extensionRunner.run(extension)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncResult.Error(e.message ?: "unknown error")
            }
            handleSyncResult(extension, result)
        }
    }

    fun syncAll() {
        val exts = extensions.value
        if (exts.isEmpty()) return

        viewModelScope.launch {
            _syncStatuses.update { current ->
                exts.associate { ext ->
                    ext.id to ExtensionSyncStatus(
                        extension = ext,
                        syncState = SyncState.SYNCING,
                        errorMessage = null,
                    )
                }
            }
            exts.map { ext ->
                async {
                    val result = try {
                        extensionRunner.run(ext)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SyncResult.Error(e.message ?: "unknown error")
                    }
                    handleSyncResult(ext, result)
                }
            }.awaitAll()
        }
    }

    fun clearAllSessions() {
        viewModelScope.launch {
            sessionStore.clearAll()
            withContext(Dispatchers.Main) {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
    }

    fun openLogin(extension: InstalledExtension) {
        val targetDomains: List<String> = try {
            gson.fromJson(extension.targetDomainsJson, object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) { emptyList() }

        val intent = LoginWebViewActivity.newIntent(
            context = context,
            extensionId = extension.id,
            loginUrl = extension.loginUrl,
            extensionName = extension.name,
            targetDomains = targetDomains,
        )
        context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    private suspend fun handleSyncResult(extension: InstalledExtension, result: SyncResult) {
        when (result) {
            is SyncResult.Success -> {
                val now = System.currentTimeMillis()
                val accountEntities = result.accounts.map { data ->
                    Account(
                        id = "${extension.id}_${data.name}",
                        extensionId = extension.id,
                        extensionName = extension.name,
                        accountName = data.name,
                        balance = data.balance,
                        currency = data.currency,
                        lastSyncAt = now,
                        accountNo = data.no,
                    )
                }
                accountDao.upsertAll(accountEntities)
                val transferEntities = result.accounts.flatMap { data ->
                    val accountId = "${extension.id}_${data.name}"
                    data.transfers.map { t ->
                        Transfer(
                            id = "${accountId}_${t.txnDateTime}",
                            accountId = accountId,
                            extensionId = extension.id,
                            txnDateTime = t.txnDateTime,
                            description = t.description,
                            amount = t.amount,
                            balance = t.balance,
                            memo = t.memo,
                        )
                    }
                }
                if (transferEntities.isNotEmpty()) {
                    transferDao.upsertAll(transferEntities)
                }
                updateStatus(extension.id) { it.copy(syncState = SyncState.SUCCESS, errorMessage = null) }
            }
            is SyncResult.Error -> {
                updateStatus(extension.id) { it.copy(syncState = SyncState.ERROR, errorMessage = result.message) }
            }
        }
    }

    fun transfersForAccount(accountId: String) = transferDao.observeByAccount(accountId)

    private fun updateStatus(id: String, update: (ExtensionSyncStatus) -> ExtensionSyncStatus) {
        val ext = extensions.value.find { it.id == id } ?: return
        _syncStatuses.update { current ->
            current.toMutableMap().also { map ->
                map[id] = update(map[id] ?: ExtensionSyncStatus(ext))
            }
        }
    }

    private fun nextExecMs(cron: String): Long {
        val next = ExecutionTime.forCron(cronParser.parse(cron))
            .nextExecution(ZonedDateTime.now())
            .orElse(null) ?: return System.currentTimeMillis()
        return next.toInstant().toEpochMilli()
    }
}
