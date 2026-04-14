package tw.kevinzhang.moneylook.ui.marketplace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.marketplace.MarketplaceRepository
import tw.kevinzhang.marketplace.RepoUrlRepository
import tw.kevinzhang.marketplace.data.ExtensionIndexEntry
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
    private val gson: Gson,
) : ViewModel() {

    val repoUrls = repoUrlRepository.observeRepoUrls()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _extensions = MutableStateFlow<List<ExtensionWithState>>(emptyList())
    val extensions = _extensions.asStateFlow()

    private val _addRepoUrl = MutableStateFlow("")
    val addRepoUrl = _addRepoUrl.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun onAddRepoUrlChanged(url: String) { _addRepoUrl.value = url }

    fun addRepo() {
        val url = _addRepoUrl.value.trim()
        if (url.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                marketplaceRepository.fetchIndex(url) // validate URL works
                repoUrlRepository.addRepoUrl(url)
                _addRepoUrl.value = ""
                loadExtensionsSuspend(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "無法載入 ${url}: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadExtensions(repoUrl: String) {
        viewModelScope.launch {
            loadExtensionsSuspend(repoUrl)
        }
    }

    private suspend fun loadExtensionsSuspend(repoUrl: String) {
        try {
            val index = marketplaceRepository.fetchIndex(repoUrl)
            val installed = installedExtensionDao.getAll()
            val installedMap = installed.associateBy { it.id }
            _extensions.value = index.map { entry ->
                val installedExt = installedMap[entry.id]
                ExtensionWithState(
                    entry = entry,
                    isInstalled = installedExt != null,
                    hasUpdate = installedExt != null && installedExt.version < entry.version,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = "載入失敗: ${e.message}"
        }
    }

    fun install(repoUrl: String, entry: ExtensionIndexEntry) {
        viewModelScope.launch {
            setLoading(entry.id, true)
            try {
                val manifest = marketplaceRepository.fetchManifest(repoUrl, entry.path)
                val scriptPath = marketplaceRepository.downloadScript(repoUrl, entry.path, entry.id)
                installedExtensionDao.insert(
                    InstalledExtension(
                        id = manifest.id,
                        name = manifest.name,
                        version = manifest.version,
                        repoUrl = repoUrl,
                        scriptCachePath = scriptPath,
                        loginUrl = manifest.loginUrl,
                        targetDomainsJson = gson.toJson(manifest.targetDomains),
                        iconUrl = manifest.iconUrl,
                    )
                )
                loadExtensionsSuspend(repoUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "安裝失敗: ${e.message}"
            } finally {
                setLoading(entry.id, false)
            }
        }
    }

    fun uninstall(extensionId: String) {
        viewModelScope.launch {
            installedExtensionDao.deleteById(extensionId)
            _extensions.value = _extensions.value.map { ext ->
                if (ext.entry.id == extensionId) ext.copy(isInstalled = false, hasUpdate = false)
                else ext
            }
        }
    }

    fun clearError() { _error.value = null }

    private fun setLoading(extensionId: String, loading: Boolean) {
        _extensions.value = _extensions.value.map { ext ->
            if (ext.entry.id == extensionId) ext.copy(isLoading = loading) else ext
        }
    }
}
