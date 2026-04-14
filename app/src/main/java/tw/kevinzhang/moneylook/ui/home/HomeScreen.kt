package tw.kevinzhang.moneylook.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.InstalledExtension

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToMarketplace: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val extensions by viewModel.extensions.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val syncStatuses by viewModel.syncStatuses.collectAsStateWithLifecycle()

    LaunchedEffect(extensions) { viewModel.refreshSessionStates() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Moneylook") },
                actions = {
                    IconButton(onClick = viewModel::syncAll) {
                        Icon(Icons.Default.Refresh, contentDescription = "同步")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToMarketplace) {
                Icon(Icons.Default.Add, contentDescription = "新增銀行")
            }
        },
    ) { innerPadding ->
        if (extensions.isEmpty()) {
            EmptyState(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                onAddExtension = onNavigateToMarketplace,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(extensions, key = { it.id }) { ext ->
                    val status = syncStatuses[ext.id]
                    val extAccounts = accounts.filter { it.extensionId == ext.id }
                    ExtensionCard(
                        extension = ext,
                        accounts = extAccounts,
                        syncState = status?.syncState ?: SyncState.IDLE,
                        hasSession = status?.hasSession ?: false,
                        errorMessage = status?.errorMessage,
                        onLogin = { viewModel.openLogin(ext) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionCard(
    extension: InstalledExtension,
    accounts: List<Account>,
    syncState: SyncState,
    hasSession: Boolean,
    errorMessage: String?,
    onLogin: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(extension.name, style = MaterialTheme.typography.titleMedium)
                when (syncState) {
                    SyncState.SYNCING -> CircularProgressIndicator(
                        modifier = Modifier.padding(4.dp),
                        strokeWidth = 2.dp,
                    )
                    SyncState.ERROR -> Text(
                        "失敗",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> {}
                }
            }

            if (!hasSession) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
                    Text("登入")
                }
            } else if (accounts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                accounts.forEach { account ->
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(account.accountName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${"%.2f".format(account.balance)} ${account.currency}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onAddExtension: () -> Unit) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("尚未新增任何銀行", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onAddExtension) { Text("前往 Marketplace") }
    }
}
