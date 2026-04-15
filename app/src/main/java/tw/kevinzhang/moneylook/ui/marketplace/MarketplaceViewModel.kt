package tw.kevinzhang.moneylook.ui.marketplace

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.marketplace.MarketplaceRepository
import tw.kevinzhang.marketplace.RepoUrlRepository
import tw.kevinzhang.marketplace.data.ExtensionIndexEntry
import tw.kevinzhang.moneylook.schedule.SchedulerManager
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

    val repoUrls = repoUrlRepository.observeRepoUrls()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // Map from repoUrl → extensions from that repo
    private val _extensionsByRepo = MutableStateFlow<Map<String, List<ExtensionWithState>>>(emptyMap())
    val extensionsByRepo = _extensionsByRepo.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        viewModelScope.launch {
            repoUrlRepository.observeRepoUrls().collect { urls ->
                // Load any repos not yet in the map
                val current = _extensionsByRepo.value
                val newUrls = urls - current.keys
                for (url in newUrls) {
                    launch { loadExtensionsSuspend(url) }
                }
                // Prune repos that were removed
                _extensionsByRepo.update { it.filterKeys { k -> k in urls } }
            }
        }
    }

    private suspend fun loadExtensionsSuspend(repoUrl: String) {
        try {
            val index = marketplaceRepository.fetchIndex(repoUrl)
            val installed = installedExtensionDao.getAll()
            val installedMap = installed.associateBy { it.id }
            val extensions = index.map { entry ->
                val installedExt = installedMap[entry.id]
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
                val syncTriggerCachePath = marketplaceRepository.downloadSyncTriggerScript(repoUrl, entry.path, entry.id)
                val scheduleCachePath = marketplaceRepository.downloadScheduleScript(repoUrl, entry.path, entry.id)
                val installed = InstalledExtension(
                    id = manifest.id,
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
        viewModelScope.launch {
            schedulerManager.cancelExtension(extensionId)
            installedExtensionDao.deleteById(extensionId)
            accountDao.deleteByExtensionId(extensionId)
            _extensionsByRepo.update { map ->
                val updated = map[repoUrl]?.map { ext ->
                    if (ext.entry.id == extensionId) ext.copy(isInstalled = false, hasUpdate = false)
                    else ext
                }
                if (updated != null) map + (repoUrl to updated) else map
            }
        }
    }

    fun clearError() { _error.value = null }

    private fun setLoading(repoUrl: String, extensionId: String, loading: Boolean) {
        _extensionsByRepo.update { map ->
            val updated = map[repoUrl]?.map { ext ->
                if (ext.entry.id == extensionId) ext.copy(isLoading = loading) else ext
            }
            if (updated != null) map + (repoUrl to updated) else map
        }
    }
}
