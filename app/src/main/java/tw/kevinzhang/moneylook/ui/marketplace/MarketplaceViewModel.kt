package tw.kevinzhang.moneylook.ui.marketplace

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.marketplace.MarketplaceRepository
import tw.kevinzhang.marketplace.RepoUrlRepository
import tw.kevinzhang.marketplace.data.ExtensionIndexEntry
import tw.kevinzhang.moneylook.schedule.ScheduleStatus
import tw.kevinzhang.moneylook.schedule.ScheduleWorker
import tw.kevinzhang.moneylook.schedule.SchedulerManager
import java.time.ZonedDateTime
import javax.inject.Inject

data class ExtensionWithState(
    val entry: ExtensionIndexEntry,
    val isInstalled: Boolean,
    val hasUpdate: Boolean,
    val isLoading: Boolean = false,
)

@HiltViewModel
class MarketplaceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val marketplaceRepository: MarketplaceRepository,
    private val repoUrlRepository: RepoUrlRepository,
    private val installedExtensionDao: InstalledExtensionDao,
    private val accountDao: AccountDao,
    private val gson: Gson,
    private val schedulerManager: SchedulerManager,
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)
    private val cronParser = CronParser(
        CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING)
    )

    val repoUrls = repoUrlRepository.observeRepoUrls()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _extensionsByRepo = MutableStateFlow<Map<String, List<ExtensionWithState>>>(emptyMap())
    val extensionsByRepo = _extensionsByRepo.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /**
     * Per-extension schedule status, keyed by extensionId.
     * Only contains entries for installed extensions that provide a schedule script.
     */
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

    init {
        viewModelScope.launch {
            repoUrlRepository.observeRepoUrls().collect { urls ->
                val current = _extensionsByRepo.value
                val newUrls = urls - current.keys
                for (url in newUrls) {
                    launch { loadExtensionsSuspend(url) }
                }
                _extensionsByRepo.update { it.filterKeys { k -> k in urls } }
            }
        }
    }

    private suspend fun loadExtensionsSuspend(repoUrl: String) {
        try {
            val index = marketplaceRepository.fetchIndex(repoUrl)
            val installed = installedExtensionDao.getAll()
            val installedMap = installed.associateBy { it.id }  // keyed by composite id
            val extensions = index.map { entry ->
                val compositeId = compositeId(entry.id, repoUrl)
                val installedExt = installedMap[compositeId]
                ExtensionWithState(
                    entry = entry,
                    isInstalled = installedExt != null,
                    hasUpdate = installedExt != null && installedExt.version < entry.version,
                )
            }
            _extensionsByRepo.update { it + (repoUrl to extensions) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = "載入失敗: ${e.message}"
        }
    }

    fun install(repoUrl: String, entry: ExtensionIndexEntry) {
        viewModelScope.launch {
            setLoading(repoUrl, entry.id, true)
            try {
                val manifest = marketplaceRepository.fetchManifest(repoUrl, entry.path)
                val compositeId = compositeId(manifest.id, repoUrl)
                val syncTriggerCachePath = marketplaceRepository.downloadSyncTriggerScript(repoUrl, entry.path, compositeId)
                val scheduleCachePath = marketplaceRepository.downloadScheduleScript(repoUrl, entry.path, compositeId)
                val installed = InstalledExtension(
                    id = compositeId,
                    manifestId = manifest.id,
                    name = manifest.name,
                    version = manifest.version,
                    repoUrl = repoUrl,
                    syncTriggerCachePath = syncTriggerCachePath,
                    loginUrl = manifest.loginUrl,
                    targetDomainsJson = gson.toJson(manifest.targetDomains),
                    iconUrl = manifest.iconUrl,
                    scheduleCachePath = scheduleCachePath,
                    scheduleCron = manifest.schedule?.cron,
                )
                installedExtensionDao.insert(installed)
                schedulerManager.scheduleExtension(installed)
                loadExtensionsSuspend(repoUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "安裝失敗: ${e.message}"
            } finally {
                setLoading(repoUrl, entry.id, false)
            }
        }
    }

    fun uninstall(repoUrl: String, extensionId: String) {
        val compositeId = compositeId(extensionId, repoUrl)
        viewModelScope.launch {
            schedulerManager.cancelExtension(compositeId)
            installedExtensionDao.deleteById(compositeId)
            accountDao.deleteByExtensionId(compositeId)
            _extensionsByRepo.update { map ->
                val updated = map[repoUrl]?.map { ext ->
                    if (ext.entry.id == extensionId) ext.copy(isInstalled = false, hasUpdate = false)
                    else ext
                }
                if (updated != null) map + (repoUrl to updated) else map
            }
        }
    }

    private fun compositeId(manifestId: String, repoUrl: String) = "$manifestId::$repoUrl"

    fun clearError() { _error.value = null }

    private fun setLoading(repoUrl: String, extensionId: String, loading: Boolean) {
        _extensionsByRepo.update { map ->
            val updated = map[repoUrl]?.map { ext ->
                if (ext.entry.id == extensionId) ext.copy(isLoading = loading) else ext
            }
            if (updated != null) map + (repoUrl to updated) else map
        }
    }

    private fun nextExecMs(cron: String): Long {
        val next = ExecutionTime.forCron(cronParser.parse(cron))
            .nextExecution(ZonedDateTime.now())
            .orElse(null) ?: return System.currentTimeMillis()
        return next.toInstant().toEpochMilli()
    }
}
