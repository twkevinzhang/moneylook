package tw.kevinzhang.moneylook.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.moneylook.schedule.ScheduleStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToMarketplace: () -> Unit,
    onNavigateToLedger: (accountId: String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val extensions by viewModel.extensions.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val syncStatuses by viewModel.syncStatuses.collectAsStateWithLifecycle()
    val scheduleStatuses by viewModel.scheduleStatuses.collectAsStateWithLifecycle()
    val countdownMs by viewModel.countdownMs.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除所有 Session") },
            text = { Text("這將清除所有銀行的登入狀態與 Cookies，需要重新登入。確定要繼續？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllSessions()
                    showClearDialog = false
                }) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Moneylook") },
                actions = {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "清除所有 Session")
                    }
                    IconButton(onClick = viewModel::syncAll) {
                        Icon(Icons.Default.Refresh, contentDescription = "全部同步")
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
                        scheduleStatus = scheduleStatuses[ext.id] ?: ScheduleStatus.None,
                        scheduleRemainingMs = countdownMs[ext.id] ?: 0L,
                        onSync = { viewModel.sync(ext) },
                        onLogin = { viewModel.openLogin(ext) },
                        onViewLedger = onNavigateToLedger,
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
    scheduleStatus: ScheduleStatus,
    scheduleRemainingMs: Long,
    onSync: () -> Unit,
    onLogin: () -> Unit,
    onViewLedger: (accountId: String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            // ── Header ───────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ExtensionIcon(extension)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = extension.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (hasSession) ScheduleStatusLabel(scheduleStatus, scheduleRemainingMs)
                }
                when {
                    syncState == SyncState.SYNCING -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    !hasSession -> Unit
                    syncState == SyncState.ERROR -> IconButton(onClick = onSync) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "重試同步",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    else -> IconButton(onClick = onSync) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "同步",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // ── Login prompt ─────────────────────────────────────────────
            if (!hasSession) {
                HorizontalDivider()
                OutlinedButton(
                    onClick = onLogin,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) { Text("登入") }
            }

            // ── Error message ─────────────────────────────────────────────
            if (errorMessage != null) {
                HorizontalDivider()
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // ── Account rows ──────────────────────────────────────────────
            if (hasSession && accounts.isNotEmpty()) {
                accounts.forEach { account ->
                    HorizontalDivider()
                    AccountRow(account, onClick = { onViewLedger(account.id) })
                }
            }
        }
    }
}

@Composable
private fun ExtensionIcon(extension: InstalledExtension) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (extension.iconUrl != null) {
            AsyncImage(
                model = extension.iconUrl,
                contentDescription = extension.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = extension.name.firstOrNull()?.toString() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AccountRow(account: Account, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.accountName,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatBalance(account),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = formatRelativeTime(account.lastSyncAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScheduleStatusLabel(status: ScheduleStatus, remainingMs: Long) {
    when (status) {
        is ScheduleStatus.None -> Unit
        is ScheduleStatus.Disabled -> Text(
            text = "排程 未啟用",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        is ScheduleStatus.Active -> Text(
            text = "排程 ${formatCountdown(remainingMs)} 後執行",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun formatBalance(account: Account): String {
    return if (account.currency == "TWD") {
        "$ %,.0f".format(account.balance)
    } else {
        "${account.currency} ${"%.2f".format(account.balance)}"
    }
}

private fun formatRelativeTime(epochMs: Long): String {
    val diffMs = System.currentTimeMillis() - epochMs
    val mins = diffMs / 60_000
    val hours = mins / 60
    val days = hours / 24
    return when {
        days > 0 -> "${days} 天前"
        hours > 0 -> "${hours} 小時前"
        mins > 0 -> "${mins} 分鐘前"
        else -> "剛剛"
    }
}

private fun formatCountdown(ms: Long): String {
    val totalSeconds = ms / 1_000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
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
