package tw.kevinzhang.moneylook.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.kevinzhang.moneylook.ai.*

@Composable
fun AiSettingsRoute(onNavigateUp: () -> Unit, viewModel: AiSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { onDispose { viewModel.cancel() } }
    AiSettingsScreen(state, viewModel::edit, viewModel::save, viewModel::testConnection, onNavigateUp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(state: AiSettingsUiState, onEdit: (AiSettings) -> Unit, onSave: () -> Unit, onTest: () -> Unit, onBack: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var showSecret by remember { mutableStateOf(false) }
    val form = state.settings
    Scaffold(topBar = {
        TopAppBar(title = { Text("AI分類設定") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("交易摘要會傳送至你選擇的 AI 服務。API 使用費用由該服務收取。", style = MaterialTheme.typography.bodyMedium)
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("服務商：${form.provider.label}") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    AiProvider.entries.forEach { provider -> DropdownMenuItem(text = { Text(provider.label) }, onClick = {
                        expanded = false; onEdit(form.copy(provider = provider, model = "", apiToken = "", accessClientId = "", accessClientSecret = ""))
                    }) }
                }
            }
            OutlinedTextField(form.model, { onEdit(form.copy(model = it)) }, label = { Text("模型名稱") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(form.apiToken, { onEdit(form.copy(apiToken = it)) }, label = { Text("API Token") }, singleLine = true, enabled = !state.busy,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "隱藏" else "顯示") } }, modifier = Modifier.fillMaxWidth())
            if (form.provider == AiProvider.CUSTOM) {
                OutlinedTextField(form.baseUrl, { onEdit(form.copy(baseUrl = it)) }, label = { Text("Base URL") }, placeholder = { Text("https://ai.example.com/v1") }, enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (form.baseUrl.isNotBlank()) Text("請求端點：${form.endpoint()}", style = MaterialTheme.typography.bodySmall)
                Text("Cloudflare Access（選填）", style = MaterialTheme.typography.titleMedium)
                Text("使用允許此服務存取的 Service Token；Client ID 與 Secret 需一起填寫。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(form.accessClientId, { onEdit(form.copy(accessClientId = it)) }, label = { Text("Client ID") }, enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(form.accessClientSecret, { onEdit(form.copy(accessClientSecret = it)) }, label = { Text("Client Secret") }, enabled = !state.busy, singleLine = true,
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "隱藏" else "顯示") } }, modifier = Modifier.fillMaxWidth())
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = onTest, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("測試連線") }
            Button(onClick = onSave, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("儲存") }
            Text("憑證加密儲存在此手機，不包含在匯出檔。儲存設定後，可至自動分類規則套用分類。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
