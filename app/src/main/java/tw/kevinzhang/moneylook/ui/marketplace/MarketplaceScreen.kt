package tw.kevinzhang.moneylook.ui.marketplace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceScreen(
    onNavigateUp: () -> Unit,
    viewModel: MarketplaceViewModel = hiltViewModel(),
) {
    val repoUrls by viewModel.repoUrls.collectAsStateWithLifecycle()
    val extensions by viewModel.extensions.collectAsStateWithLifecycle()
    val addRepoUrl by viewModel.addRepoUrl.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    LaunchedEffect(repoUrls) {
        repoUrls.firstOrNull()?.let { viewModel.loadExtensions(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Marketplace") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            item {
                AddRepoSection(
                    url = addRepoUrl,
                    isLoading = isLoading,
                    error = error,
                    onUrlChanged = viewModel::onAddRepoUrlChanged,
                    onAdd = viewModel::addRepo,
                    onClearError = viewModel::clearError,
                )
                HorizontalDivider()
            }

            if (extensions.isNotEmpty()) {
                item {
                    Text(
                        text = "可安裝的 Extensions",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(extensions, key = { it.entry.id }) { ext ->
                    val firstRepoUrl = repoUrls.firstOrNull() ?: return@items
                    ExtensionItem(
                        extensionWithState = ext,
                        onInstall = { viewModel.install(firstRepoUrl, ext.entry) },
                        onUninstall = { viewModel.uninstall(ext.entry.id) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun AddRepoSection(
    url: String,
    isLoading: Boolean,
    error: String?,
    onUrlChanged: (String) -> Unit,
    onAdd: () -> Unit,
    onClearError: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("新增 Extension 來源", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = url,
            onValueChange = {
                onUrlChanged(it)
                if (error != null) onClearError()
            },
            label = { Text("GitHub repo URL") },
            placeholder = { Text("https://github.com/owner/moneylook-extensions") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onAdd,
            enabled = url.isNotBlank() && !isLoading,
            modifier = Modifier.align(Alignment.End),
        ) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("新增")
        }
    }
}

@Composable
private fun ExtensionItem(
    extensionWithState: ExtensionWithState,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val ext = extensionWithState
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(ext.entry.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "v${ext.entry.versionName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            ext.isLoading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            ext.hasUpdate -> Button(onClick = onInstall) { Text("更新") }
            ext.isInstalled -> OutlinedButton(onClick = onUninstall) { Text("移除") }
            else -> Button(onClick = onInstall) { Text("安裝") }
        }
    }
}
