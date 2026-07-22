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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.moneylook.schedule.ScheduleStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    bottomBar: @Composable () -> Unit = {},
    onNavigateToMarketplace: () -> Unit,
    onNavigateToLedger: (accountId: String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val extensions by viewModel.extensions.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val syncStatuses by viewModel.syncStatuses.collectAsStateWithLifecycle()
    val credentialSummaries by viewModel.credentialSummaries.collectAsStateWithLifecycle()
    val scheduleStatuses by viewModel.scheduleStatuses.collectAsStateWithLifecycle()
    val countdownMs by viewModel.countdownMs.collectAsStateWithLifecycle()
    var showSyncDialog by remember { mutableStateOf(false) }
    var editingExtension by remember { mutableStateOf<InstalledExtension?>(null) }
    val hasPartialData = syncStatuses.values.any { status ->
        status.syncState == SyncState.PARTIAL || status.syncState == SyncState.ERROR
    }
    val overview = remember(accounts, hasPartialData) {
        homeOverviewPresentation(
            accounts = accounts,
            hasPartialData = hasPartialData,
        )
    }

    editingExtension?.let { extension ->
        CredentialEditDialog(
            extension = extension,
            summary = credentialSummaries[extension.id],
            onDismiss = { editingExtension = null },
            onSave = { values, enabled, cron, timezone ->
                viewModel.saveCredentials(extension, values, enabled, cron, timezone)
                editingExtension = null
            },
            onDelete = {
                viewModel.deleteCredentials(extension.id)
                editingExtension = null
            },
        )
    }

    if (showSyncDialog) {
        AlertDialog(
            onDismissRequest = { showSyncDialog = false },
            title = { Text("全部同步") },
            text = { Text("將依序重新登入並同步所有已設定帳密的銀行，確定要繼續？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.syncAll()
                    showSyncDialog = false
                }) { Text("同步") }
            },
            dismissButton = {
                TextButton(onClick = { showSyncDialog = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Moneylook") },
                actions = {
                    IconButton(onClick = onNavigateToMarketplace) {
                        Icon(Icons.Default.Store, contentDescription = "Marketplace")
                    }
                },
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = {
            FloatingActionButton(onClick = { showSyncDialog = true }) {
                Icon(Icons.Default.Sync, contentDescription = "全部同步")
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "home-overview") {
                HomeOverviewCard(
                    overview = overview,
                )
            }
            if (extensions.isEmpty()) {
                item(key = "no-extensions") {
                    EmptyState(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        onAddExtension = onNavigateToMarketplace,
                    )
                }
            } else {
                items(extensions, key = { it.id }) { ext ->
                    val status = syncStatuses[ext.id]
                    val extAccounts = accounts.filter { it.extensionId == ext.id }
                    ExtensionCard(
                        extension = ext,
                        accounts = extAccounts,
                        syncState = status?.syncState ?: SyncState.IDLE,
                        hasCredentials = status?.hasCredentials ?: false,
                        credentialSummary = credentialSummaries[ext.id],
                        errorMessage = status?.errorMessage,
                        scheduleStatus = scheduleStatuses[ext.id] ?: ScheduleStatus.None,
                        scheduleRemainingMs = countdownMs[ext.id] ?: 0L,
                        onSync = { viewModel.sync(ext) },
                        onEditCredentials = { editingExtension = ext },
                        onViewLedger = onNavigateToLedger,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeOverviewCard(
    overview: HomeOverviewPresentation,
) {
    var selectedSection by rememberSaveable { mutableStateOf(OverviewSection.ASSETS) }
    val selectedLabel = if (selectedSection == OverviewSection.ASSETS) "資產" else "負債"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "總覽",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "依幣別彙總，未進行匯率換算",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "淨資產",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (!overview.hasAccounts) {
                Text(
                    text = "尚無帳戶資料",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                overview.currencies.forEach { summary ->
                    OverviewAmountRow(
                        currency = summary.currency,
                        amount = summary.netWorth,
                        emphasized = true,
                    )
                }
            }

            if (overview.hasAccounts) {
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedSection == OverviewSection.ASSETS,
                        onClick = { selectedSection = OverviewSection.ASSETS },
                        label = { Text("資產") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = selectedSection == OverviewSection.LIABILITIES,
                        onClick = { selectedSection = OverviewSection.LIABILITIES },
                        label = { Text("負債") },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = selectedLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                overview.currencies.forEach { summary ->
                    OverviewAmountRow(
                        currency = summary.currency,
                        amount = if (selectedSection == OverviewSection.ASSETS) {
                            summary.assets
                        } else {
                            summary.liabilities
                        },
                        emphasized = false,
                    )
                }
            }

            if (overview.hasPartialData) {
                HorizontalDivider(modifier = Modifier.padding(top = 16.dp, bottom = 10.dp))
                Text(
                    text = if (overview.hasAccounts) {
                        "部分資料可能尚未更新，已保留最後成功同步的餘額。"
                    } else {
                        "部分資料同步失敗，尚無可顯示的帳戶餘額。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun OverviewAmountRow(
    currency: String,
    amount: Double,
    emphasized: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (emphasized) 6.dp else 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = currency,
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = formatCurrencyAmount(amount, currency),
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun ExtensionCard(
    extension: InstalledExtension,
    accounts: List<Account>,
    syncState: SyncState,
    hasCredentials: Boolean,
    credentialSummary: CredentialSummary?,
    errorMessage: String?,
    scheduleStatus: ScheduleStatus,
    scheduleRemainingMs: Long,
    onSync: () -> Unit,
    onEditCredentials: () -> Unit,
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
                    if (hasCredentials) ScheduleStatusLabel(scheduleStatus, scheduleRemainingMs)
                }
                when {
                    syncState == SyncState.SYNCING -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    !hasCredentials -> Unit
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

            if (!hasCredentials) {
                HorizontalDivider()
                OutlinedButton(
                    onClick = onEditCredentials,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) { Text("設定登入資料") }
            } else {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = credentialSummary?.summaryText?.ifBlank { "登入資料已設定" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onEditCredentials) {
                        Icon(Icons.Default.Edit, contentDescription = "編輯登入資料與排程")
                    }
                }
            }

            // ── Sync message ──────────────────────────────────────────────
            if (errorMessage != null) {
                HorizontalDivider()
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (syncState == SyncState.PARTIAL) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // ── Account rows ──────────────────────────────────────────────
            if (accounts.isNotEmpty()) {
                accounts.forEach { account ->
                    HorizontalDivider()
                    AccountRow(
                        account = account,
                        onClick = { onViewLedger(account.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CredentialEditDialog(
    extension: InstalledExtension,
    summary: CredentialSummary?,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>, Boolean, String, String) -> Unit,
    onDelete: () -> Unit,
) {
    val fields = summary?.fields ?: LEGACY_CREDENTIAL_FIELD_DEFINITIONS
    var credentialValues by remember(extension.id, summary?.visibleValues, summary?.storedPasswordKeys) {
        mutableStateOf(
            fields.associate { field ->
                field.key to if (field.isPassword) "" else summary?.visibleValues?.get(field.key).orEmpty()
            },
        )
    }
    var scheduleEnabled by remember(extension.id, summary?.scheduleEnabled) {
        mutableStateOf(summary?.scheduleEnabled ?: extension.suggestedScheduleEnabled)
    }
    var cron by remember(extension.id, summary?.scheduleCron) {
        mutableStateOf(summary?.scheduleCron ?: extension.suggestedScheduleCron ?: "0 8 * * *")
    }
    var timezone by remember(extension.id, summary?.timezoneId) {
        mutableStateOf(summary?.timezoneId ?: extension.suggestedScheduleTimezone)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${extension.name} 登入資料與排程") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "登入資料會以明碼 JSON 保存在此裝置的 App 私有資料庫，並完整提供給擴充腳本。擴充可向任意網址送出登入資料與其他資料；請只安裝並更新你完全信任的擴充。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "儲存即同意目前及之後由你自主下載的擴充版本，繼續取得這組登入資料並自由發出網路請求。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                fields.forEach { field ->
                    val hasStoredPassword = field.key in summary?.storedPasswordKeys.orEmpty()
                    val label = when {
                        field.isPassword && hasStoredPassword -> "新${field.label}（留空保留原值）"
                        field.required -> "${field.label}（必填）"
                        else -> field.label
                    }
                    OutlinedTextField(
                        value = credentialValues[field.key].orEmpty(),
                        onValueChange = { credentialValues = credentialValues + (field.key to it) },
                        label = { Text(label) },
                        visualTransformation = if (field.isPassword) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("啟用建議排程", modifier = Modifier.weight(1f))
                    Switch(checked = scheduleEnabled, onCheckedChange = { scheduleEnabled = it })
                }
                OutlinedTextField(
                    value = cron,
                    onValueChange = { cron = it },
                    enabled = scheduleEnabled,
                    label = { Text("Cron（UNIX 五欄）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = timezone,
                    onValueChange = { timezone = it },
                    enabled = scheduleEnabled,
                    label = { Text("時區") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(credentialValues, scheduleEnabled, cron, timezone)
                },
            ) { Text("儲存") }
        },
        dismissButton = {
            Row {
                if (summary?.isConfigured == true) {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text("刪除登入資料")
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
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
private fun AccountRow(
    account: Account,
    onClick: () -> Unit,
) {
    val presentation = accountRowPresentation(
        kind = account.kind,
        balance = account.balance,
        currency = account.currency,
        availableCredit = account.availableCredit,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = accountAssetIcon(account.kind),
            contentDescription = accountAssetIconDescription(account.kind),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = account.accountName,
                style = MaterialTheme.typography.bodyMedium,
            )
            presentation.supportingText?.let { supportingText ->
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = presentation.primaryAmount,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (presentation.isLiability) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = formatRelativeTime(account.lastSyncAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun accountAssetIcon(kind: AssetKind) = when (kind) {
    AssetKind.DEPOSIT -> Icons.Default.AccountBalance
    AssetKind.TIME_DEPOSIT -> Icons.Default.AccountBalance
    AssetKind.CREDIT_CARD -> Icons.Default.CreditCard
    AssetKind.LOAN -> Icons.Default.Payments
}

private fun accountAssetIconDescription(kind: AssetKind) = when (kind) {
    AssetKind.DEPOSIT -> "存款帳戶"
    AssetKind.TIME_DEPOSIT -> "定期存款"
    AssetKind.CREDIT_CARD -> "信用卡"
    AssetKind.LOAN -> "貸款"
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
