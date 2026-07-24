package tw.kevinzhang.moneylook.ui.marketplace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val action: MarketplaceExtensionAction,
    val isLoading: Boolean = false,
)

enum class MarketplaceExtensionAction {
    INSTALL,
    UPDATE,
    REMOVE,
    INSTALLED_FROM_OTHER_SOURCE,
}

internal fun resolveMarketplaceAction(
    entry: ExtensionIndexEntry,
    repoUrl: String,
    installedExtensions: List<InstalledExtension>,
): MarketplaceExtensionAction {
    val exactId = extensionCompositeId(entry.id, repoUrl)
    val exact = installedExtensions.firstOrNull { it.id == exactId }
    if (exact != null) {
        return if (exact.version < entry.version) {
            MarketplaceExtensionAction.UPDATE
        } else {
            MarketplaceExtensionAction.REMOVE
        }
    }
    return if (installedExtensions.any { it.manifestId == entry.id }) {
        MarketplaceExtensionAction.INSTALLED_FROM_OTHER_SOURCE
    } else {
        MarketplaceExtensionAction.INSTALL
    }
}

internal fun extensionCompositeId(manifestId: String, repoUrl: String) = "$manifestId::$repoUrl"

@HiltViewModel
class MarketplaceViewModel @Inject constructor(
    private val marketplaceRepository: MarketplaceRepository,
    private val repoUrlRepository: RepoUrlRepository,
    private val installedExtensionDao: InstalledExtensionDao,
    private val accountDao: AccountDao,
    private val schedulerManager: SchedulerManager,
    private val gson: Gson,
) : ViewModel() {

    val repoUrls = repoUrlRepository.observeRepoUrls()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _extensionsByRepo = MutableStateFlow<Map<String, List<ExtensionWithState>>>(emptyMap())
    val extensionsByRepo = _extensionsByRepo.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

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
        viewModelScope.launch {
            installedExtensionDao.observeAll().collect(::refreshInstalledStates)
        }
    }

    private suspend fun loadExtensionsSuspend(repoUrl: String) {
        try {
            val index = marketplaceRepository.fetchIndex(repoUrl)
            val installed = installedExtensionDao.getAll()
            val extensions = index.map { entry ->
                ExtensionWithState(
                    entry = entry,
                    action = resolveMarketplaceAction(entry, repoUrl, installed),
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
                check(manifest.id == entry.id) {
                    "Manifest id 與 Marketplace index 不一致"
                }
                val compositeId = extensionCompositeId(manifest.id, repoUrl)
                val existing = installedExtensionDao.getByManifestId(manifest.id)
                check(existing == null || existing.id == compositeId) {
                    "此 Extension 已由其他來源安裝"
                }
                val artifact = marketplaceRepository.downloadSyncTriggerScript(repoUrl, entry.path, compositeId)
                val installed = InstalledExtension(
                    id = compositeId,
                    manifestId = manifest.id,
                    name = manifest.name,
                    version = manifest.version,
                    repoUrl = repoUrl,
                    syncTriggerCachePath = artifact.path,
                    iconUrl = manifest.iconUrl,
                    suggestedScheduleCron = manifest.schedule?.suggestedCron,
                    suggestedScheduleTimezone = manifest.schedule?.suggestedTimezone ?: "Asia/Taipei",
                    suggestedScheduleEnabled = manifest.schedule != null,
                    credentialFieldsJson = gson.toJson(manifest.credential.fields),
                    artifactRevision = artifact.immutableRevision,
                    artifactSha256 = artifact.sha256,
                )
                check(installedExtensionDao.upsertUnlessInstalledFromOtherSource(installed)) {
                    "此 Extension 已由其他來源安裝"
                }
                refreshInstalledStates(installedExtensionDao.getAll())
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
        val compositeId = extensionCompositeId(extensionId, repoUrl)
        viewModelScope.launch {
            schedulerManager.cancelExtension(compositeId)
            installedExtensionDao.deleteById(compositeId)
            accountDao.deleteByExtensionId(compositeId)
            refreshInstalledStates(installedExtensionDao.getAll())
        }
    }

    fun clearError() { _error.value = null }

    private fun refreshInstalledStates(installed: List<InstalledExtension>) {
        _extensionsByRepo.update { repos ->
            repos.mapValues { (repoUrl, extensions) ->
                extensions.map { extension ->
                    extension.copy(
                        action = resolveMarketplaceAction(extension.entry, repoUrl, installed),
                    )
                }
            }
        }
    }

    private fun setLoading(repoUrl: String, extensionId: String, loading: Boolean) {
        _extensionsByRepo.update { map ->
            val updated = map[repoUrl]?.map { ext ->
                if (ext.entry.id == extensionId) ext.copy(isLoading = loading) else ext
            }
            if (updated != null) map + (repoUrl to updated) else map
        }
    }
}
