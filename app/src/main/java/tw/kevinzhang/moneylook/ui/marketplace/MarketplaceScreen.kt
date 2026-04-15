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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tw.kevinzhang.moneylook.schedule.ScheduleStatus

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MarketplaceScreen(
    onNavigateUp: () -> Unit,
    onNavigateToManageRepos: () -> Unit,
    viewModel: MarketplaceViewModel = hiltViewModel(),
) {
    val extensionsByRepo by viewModel.extensionsByRepo.collectAsStateWithLifecycle()
    val scheduleStatuses by viewModel.scheduleStatuses.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Marketplace") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToManageRepos) {
                        Icon(Icons.Filled.Settings, contentDescription = "管理 Repos")
                    }
                },
            )
        }
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
                        scheduleStatus = scheduleStatuses[ext.entry.id] ?: ScheduleStatus.None,
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
    scheduleStatus: ScheduleStatus,
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
            ScheduleStatusBadge(scheduleStatus)
        }
        when {
            ext.isLoading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            ext.hasUpdate -> Button(onClick = onInstall) { Text("更新") }
            ext.isInstalled -> OutlinedButton(onClick = onUninstall) { Text("移除") }
            else -> Button(onClick = onInstall) { Text("安裝") }
        }
    }
}

@Composable
private fun ScheduleStatusBadge(status: ScheduleStatus) {
    when (status) {
        is ScheduleStatus.None -> Unit

        is ScheduleStatus.Disabled -> Text(
            text = "排程 未啟用",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp),
        )

        is ScheduleStatus.Active -> {
            val nextExecMs = status.nextExecMs
            var remainingMs by remember(nextExecMs) {
                mutableLongStateOf((nextExecMs - System.currentTimeMillis()).coerceAtLeast(0L))
            }
            LaunchedEffect(nextExecMs) {
                while (remainingMs > 0L) {
                    delay(1_000L)
                    remainingMs = (nextExecMs - System.currentTimeMillis()).coerceAtLeast(0L)
                }
            }
            Text(
                text = "排程 下次執行：${formatCountdown(remainingMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
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
