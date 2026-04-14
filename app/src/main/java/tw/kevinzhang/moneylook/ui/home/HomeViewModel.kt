package tw.kevinzhang.moneylook.ui.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.ExtensionRunner
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.session.SessionStore
import tw.kevinzhang.moneylook.ui.login.LoginWebViewActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    private val extensionRunner: ExtensionRunner,
    private val sessionStore: SessionStore,
    private val gson: Gson,
) : ViewModel() {

    val accounts = accountDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val extensions = installedExtensionDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncStatuses = MutableStateFlow<Map<String, ExtensionSyncStatus>>(emptyMap())
    val syncStatuses = _syncStatuses.asStateFlow()

    fun refreshSessionStates() {
        _syncStatuses.update { current ->
            extensions.value.associate { ext ->
                ext.id to (current[ext.id]?.copy(hasSession = sessionStore.hasSession(ext.id))
                    ?: ExtensionSyncStatus(ext, hasSession = sessionStore.hasSession(ext.id)))
            }
        }
    }

    fun syncAll() {
        val exts = extensions.value
        if (exts.isEmpty()) return

        viewModelScope.launch {
            // Mark all as SYNCING
            _syncStatuses.update { current ->
                exts.associate { ext ->
                    ext.id to (current[ext.id]?.copy(syncState = SyncState.SYNCING)
                        ?: ExtensionSyncStatus(ext, SyncState.SYNCING))
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

                    when (result) {
                        is SyncResult.Success -> {
                            val now = System.currentTimeMillis()
                            val accountEntities = result.accounts.map { data ->
                                Account(
                                    id = "${ext.id}_${data.name}",
                                    extensionId = ext.id,
                                    extensionName = ext.name,
                                    accountName = data.name,
                                    balance = data.balance,
                                    currency = data.currency,
                                    lastSyncAt = now,
                                )
                            }
                            accountDao.upsertAll(accountEntities)
                            updateStatus(ext.id) { it.copy(syncState = SyncState.SUCCESS, errorMessage = null) }
                        }
                        is SyncResult.Error -> {
                            updateStatus(ext.id) { it.copy(syncState = SyncState.ERROR, errorMessage = result.message) }
                        }
                    }
                }
            }.awaitAll()
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

    private fun updateStatus(id: String, update: (ExtensionSyncStatus) -> ExtensionSyncStatus) {
        _syncStatuses.update { current ->
            current.toMutableMap().also { map ->
                map[id]?.let { map[id] = update(it) }
            }
        }
    }
}
