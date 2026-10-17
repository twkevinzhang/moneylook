package tw.kevinzhang.moneylook.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tw.kevinzhang.moneylook.ai.*
import javax.inject.Inject

data class AiSettingsUiState(
    val settings: AiSettings = AiSettings(),
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class AiSettingsViewModel @Inject constructor(private val store: AiSettingsStore, private val client: AiClassificationClient) : ViewModel() {
    private val mutableState = MutableStateFlow(AiSettingsUiState(busy = true))
    val state = mutableState.asStateFlow()
    private var task: Job? = null
    init {
        task = viewModelScope.launch {
            mutableState.value = when (val result = store.load()) {
                is AiSettingsLoadResult.Ready -> AiSettingsUiState(result.settings)
                AiSettingsLoadResult.Missing -> AiSettingsUiState()
                AiSettingsLoadResult.CredentialsUnavailable -> AiSettingsUiState(message = "無法解密已儲存憑證，請重新輸入並儲存")
            }
        }
    }
    fun edit(settings: AiSettings) {
        if (!state.value.busy) mutableState.value = AiSettingsUiState(settings)
    }
    fun save() = perform(test = false)
    fun testConnection() = perform(test = true)
    private fun perform(test: Boolean) {
        if (state.value.busy) return
        val form = state.value.settings
        val errors = validateAiSettings(form)
        if (errors.isNotEmpty()) { mutableState.value = state.value.copy(message = errors.joinToString("\n")); return }
        mutableState.value = state.value.copy(busy = true, message = if (test) "正在測試連線…" else "正在儲存…")
        task = viewModelScope.launch {
            val message = try {
                if (test) { client.testConnection(form); "連線成功，模型已回傳有效分類" }
                else { store.save(form); "AI 分類設定已儲存" }
            } catch (_: TimeoutCancellationException) { "連線逾時，請檢查服務後重試" }
            catch (e: CancellationException) { throw e }
            catch (e: AiClassificationException) { e.code.message }
            catch (_: Exception) { if (test) "測試連線失敗，請檢查設定" else "無法儲存設定，請重試" }
            mutableState.value = state.value.copy(busy = false, message = message)
        }
    }
    fun cancel() {
        task?.cancel()
        mutableState.value = state.value.copy(busy = false, message = null)
    }
}
