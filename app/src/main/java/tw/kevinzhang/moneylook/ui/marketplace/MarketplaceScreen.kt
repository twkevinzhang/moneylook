package tw.kevinzhang.moneylook.ui.marketplace

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MarketplaceScreen(
    bottomBar: @Composable () -> Unit = {},
    onNavigateToManageRepos: () -> Unit,
    viewModel: MarketplaceViewModel = hiltViewModel(),
) {
    val extensionsByRepo by viewModel.extensionsByRepo.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Marketplace") },
                actions = {
                    IconButton(onClick = onNavigateToManageRepos) {
                        Icon(Icons.Filled.Settings, contentDescription = "管理 Repos")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = bottomBar,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            extensionsByRepo.forEach { (repoUrl, extensions) ->
                stickyHeader(key = "header_$repoUrl") {
                    Text(
                        text = repoUrl.repoDisplayName(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(extensions, key = { "${repoUrl}_${it.entry.id}" }) { ext ->
                    ExtensionItem(
                        extensionWithState = ext,
                        onInstall = { viewModel.install(repoUrl, ext.entry) },
                        onUninstall = { viewModel.uninstall(repoUrl, ext.entry.id) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }
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
            ext.action == MarketplaceExtensionAction.UPDATE -> Button(onClick = onInstall) { Text("更新") }
            ext.action == MarketplaceExtensionAction.REMOVE -> OutlinedButton(onClick = onUninstall) { Text("移除") }
            ext.action == MarketplaceExtensionAction.INSTALLED_FROM_OTHER_SOURCE -> {
                OutlinedButton(onClick = {}, enabled = false) { Text("已由其他來源安裝") }
            }
            else -> Button(onClick = onInstall) { Text("安裝") }
        }
    }
}
