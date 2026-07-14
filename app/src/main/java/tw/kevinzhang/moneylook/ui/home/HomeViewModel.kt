package tw.kevinzhang.moneylook.ui.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.CredentialProfileDao
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.db.TransferDao
import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.moneylook.schedule.ScheduleStatus
import tw.kevinzhang.moneylook.schedule.ScheduleWorker
import tw.kevinzhang.moneylook.schedule.SchedulerManager
import tw.kevinzhang.moneylook.sync.BankSyncCoordinator
import tw.kevinzhang.moneylook.sync.SyncResultPersister
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

enum class SyncState { IDLE, SYNCING, SUCCESS, ERROR }

data class ExtensionSyncStatus(
    val extension: InstalledExtension,
    val syncState: SyncState = SyncState.IDLE,
    val errorMessage: String? = null,
    val hasCredentials: Boolean = false,
)

data class CredentialSummary(
    val extensionId: String,
    val username: String,
    val scheduleEnabled: Boolean,
    val scheduleCron: String,
    val timezoneId: String,
    val lastRunAt: Long?,
    val lastRunStatus: String?,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val installedExtensionDao: InstalledExtensionDao,
    private val accountDao: AccountDao,
    private val transferDao: TransferDao,
    private val credentialProfileDao: CredentialProfileDao,
    private val syncCoordinator: BankSyncCoordinator,
    private val syncResultPersister: SyncResultPersister,
    private val schedulerManager: SchedulerManager,
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)
    private val cronParser = CronParser(
        CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX),
    )

    val accounts = accountDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val extensions = installedExtensionDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val credentialProfiles = credentialProfileDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val credentialSummaries: StateFlow<Map<String, CredentialSummary>> = credentialProfiles
        .map { profiles ->
            profiles.associate { profile ->
                profile.extensionId to CredentialSummary(
                    extensionId = profile.extensionId,
                    username = profile.username,
                    scheduleEnabled = profile.scheduleEnabled,
                    scheduleCron = profile.scheduleCron,
                    timezoneId = profile.timezoneId,
                    lastRunAt = profile.lastRunAt,
                    lastRunStatus = profile.lastRunStatus,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _syncStatuses = MutableStateFlow<Map<String, ExtensionSyncStatus>>(emptyMap())

    val syncStatuses: StateFlow<Map<String, ExtensionSyncStatus>> =
        combine(_syncStatuses, credentialProfiles, installedExtensionDao.observeAll()) { mutable, profiles, exts ->
            val configuredIds = profiles
                .filter { it.username.isNotBlank() && it.password.isNotEmpty() }
                .mapTo(mutableSetOf()) { it.extensionId }
            exts.associate { ext ->
                val current = mutable[ext.id]
                ext.id to ExtensionSyncStatus(
                    extension = ext,
                    syncState = current?.syncState ?: SyncState.IDLE,
                    errorMessage = current?.errorMessage,
                    hasCredentials = ext.id in configuredIds,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    val scheduleStatuses: StateFlow<Map<String, ScheduleStatus>> = credentialProfiles
        .flatMapLatest { profiles ->
            if (profiles.isEmpty()) return@flatMapLatest flowOf(emptyMap())
            val perProfile = profiles.map { profile ->
                workManager.getWorkInfosByTagFlow(ScheduleWorker.tag(profile.extensionId)).map { workInfos ->
                    val active = workInfos.any {
                        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                    }
                    val status = when {
                        !profile.scheduleEnabled -> ScheduleStatus.Disabled
                        active -> ScheduleStatus.Active(nextExecMs(profile.scheduleCron, profile.timezoneId))
                        else -> ScheduleStatus.Disabled
                    }
                    profile.extensionId to status
                }
            }
            combine(perProfile) { pairs -> pairs.associate { it } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private fun tickerFlow() = flow {
        while (true) {
            emit(Unit)
            delay(1_000L)
        }
    }

    val countdownMs: StateFlow<Map<String, Long>> =
        combine(scheduleStatuses, tickerFlow()) { statuses, _ ->
            statuses.mapValues { (_, status) ->
                when (status) {
                    is ScheduleStatus.Active -> (status.nextExecMs - System.currentTimeMillis()).coerceAtLeast(0L)
                    else -> 0L
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun sync(extension: InstalledExtension) {
        viewModelScope.launch {
            val profile = credentialProfileDao.getByExtensionId(extension.id)
            if (profile == null || profile.username.isBlank() || profile.password.isEmpty()) {
                updateStatus(extension.id) {
                    it.copy(syncState = SyncState.ERROR, errorMessage = "請先設定網銀帳號密碼")
                }
                return@launch
            }
            updateStatus(extension.id) { it.copy(syncState = SyncState.SYNCING, errorMessage = null) }
            val result = runSync(extension, profile)
            handleSyncResult(extension, result)
        }
    }

    fun syncAll() {
        val profilesById = credentialProfiles.value.associateBy { it.extensionId }
        val runnable = extensions.value.mapNotNull { ext -> profilesById[ext.id]?.let { ext to it } }
            .filter { (_, profile) -> profile.username.isNotBlank() && profile.password.isNotEmpty() }
        if (runnable.isEmpty()) return

        viewModelScope.launch {
            _syncStatuses.update { current ->
                current.toMutableMap().also { statuses ->
                    runnable.forEach { (ext, _) ->
                        statuses[ext.id] = ExtensionSyncStatus(
                            extension = ext,
                            syncState = SyncState.SYNCING,
                            hasCredentials = true,
                        )
                    }
                }
            }
            runnable.map { (ext, profile) ->
                async { handleSyncResult(ext, runSync(ext, profile)) }
            }.awaitAll()
        }
    }

    fun saveCredentials(
        extension: InstalledExtension,
        username: String,
        password: String,
        scheduleEnabled: Boolean,
        scheduleCron: String,
        timezoneId: String,
    ) {
        viewModelScope.launch {
            val existing = credentialProfileDao.getByExtensionId(extension.id)
            val resolvedPassword = password.ifEmpty { existing?.password.orEmpty() }
            if (username.isBlank() || resolvedPassword.isEmpty()) {
                updateStatus(extension.id) {
                    it.copy(syncState = SyncState.ERROR, errorMessage = "帳號與密碼不可空白")
                }
                return@launch
            }
            if (scheduleEnabled && !isValidSchedule(scheduleCron, timezoneId)) {
                updateStatus(extension.id) {
                    it.copy(syncState = SyncState.ERROR, errorMessage = "排程或時區格式不正確")
                }
                return@launch
            }
            val profile = CredentialProfile(
                extensionId = extension.id,
                username = username.trim(),
                password = resolvedPassword,
                approvedLoginHost = Uri.parse(extension.loginUrl).host.orEmpty().lowercase(),
                approvedDomainsJson = extension.targetDomainsJson,
                scheduleEnabled = scheduleEnabled,
                scheduleCron = scheduleCron.trim(),
                timezoneId = timezoneId.trim(),
                lastRunAt = existing?.lastRunAt,
                lastRunStatus = existing?.lastRunStatus,
            )
            credentialProfileDao.upsert(profile)
            if (scheduleEnabled) schedulerManager.scheduleProfile(profile)
            else schedulerManager.cancelExtension(extension.id)
            updateStatus(extension.id) { it.copy(syncState = SyncState.IDLE, errorMessage = null) }
        }
    }

    fun deleteCredentials(extensionId: String) {
        viewModelScope.launch {
            schedulerManager.cancelExtension(extensionId)
            credentialProfileDao.deleteByExtensionId(extensionId)
            updateStatus(extensionId) { it.copy(syncState = SyncState.IDLE, errorMessage = null) }
        }
    }

    private suspend fun runSync(extension: InstalledExtension, profile: CredentialProfile): SyncResult = try {
        syncCoordinator.sync(extension, profile)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SyncResult.Error(e.message ?: "unknown error")
    }

    private suspend fun handleSyncResult(extension: InstalledExtension, result: SyncResult) {
        val now = System.currentTimeMillis()
        when (result) {
            is SyncResult.Success -> {
                syncResultPersister.persist(extension, result)
                credentialProfileDao.updateLastRun(extension.id, now, "success")
                updateStatus(extension.id) { it.copy(syncState = SyncState.SUCCESS, errorMessage = null) }
            }
            is SyncResult.Error -> {
                credentialProfileDao.updateLastRun(extension.id, now, "error:${result.message.take(200)}")
                updateStatus(extension.id) {
                    it.copy(syncState = SyncState.ERROR, errorMessage = result.message)
                }
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

    private fun isValidSchedule(cron: String, timezoneId: String): Boolean = runCatching {
        ZoneId.of(timezoneId)
        cronParser.parse(cron).validate()
    }.isSuccess

    private fun nextExecMs(cron: String, timezoneId: String): Long {
        val zone = runCatching { ZoneId.of(timezoneId) }.getOrNull() ?: return System.currentTimeMillis()
        return runCatching {
            ExecutionTime.forCron(cronParser.parse(cron))
                .nextExecution(ZonedDateTime.now(zone))
                .orElse(null)
                ?.toInstant()
                ?.toEpochMilli()
        }.getOrNull() ?: System.currentTimeMillis()
    }
}
