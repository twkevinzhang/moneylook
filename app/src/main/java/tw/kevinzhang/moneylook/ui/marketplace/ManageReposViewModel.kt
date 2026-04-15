package tw.kevinzhang.moneylook.ui.marketplace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tw.kevinzhang.marketplace.MarketplaceRepository
import tw.kevinzhang.marketplace.RepoUrlRepository
import javax.inject.Inject

@HiltViewModel
class ManageReposViewModel @Inject constructor(
    private val marketplaceRepository: MarketplaceRepository,
    private val repoUrlRepository: RepoUrlRepository,
) : ViewModel() {

    val repoUrls = repoUrlRepository.observeRepoUrls()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

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
                marketplaceRepository.fetchIndex(url)
                repoUrlRepository.addRepoUrl(url)
                _addRepoUrl.value = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "無法載入 $url: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun removeRepo(url: String) {
        viewModelScope.launch { repoUrlRepository.removeRepoUrl(url) }
    }

    fun clearError() { _error.value = null }
}
