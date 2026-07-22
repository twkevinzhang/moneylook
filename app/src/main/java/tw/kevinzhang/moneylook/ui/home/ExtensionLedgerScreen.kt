package tw.kevinzhang.moneylook.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.db.TransferListItem
import tw.kevinzhang.moneylook.ui.transactions.ClassificationViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionLedgerScreen(
    accountId: String,
    onNavigateUp: () -> Unit,
    onNavigateToTransaction: (transferId: String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    classificationViewModel: ClassificationViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    val transfers by classificationViewModel.transfersForAccount(accountId)
        .collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("交易明細") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        ExtensionLedgerAnnotatedContent(
            account = account,
            transfers = transfers,
            onNavigateToTransaction = onNavigateToTransaction,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }
}

@Composable
private fun ExtensionLedgerAnnotatedContent(
    account: Account?,
    transfers: List<TransferListItem>,
    onNavigateToTransaction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (account == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) { Text("找不到帳戶") }
        return
    }
    val groupedTransfers = transfers.groupBy { ledgerMonthKey(it.transfer) }
    LazyColumn(modifier = modifier) {
        item(key = "account-header") { LedgerAccountHeader(account) }
        if (account.transferSyncComplete == false) item(key = "partial-history") {
            AssistChip(onClick = {}, enabled = false, label = { Text("部分明細尚未同步完成") }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
        if (transfers.isEmpty()) item(key = "empty") {
            Box(modifier = Modifier.fillParentMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text("尚無交易紀錄") }
        }
        groupedTransfers.forEach { (month, monthTransfers) ->
            stickyHeader(key = "month-$month") {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Text(ledgerMonthLabel(month), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
            items(monthTransfers, key = { it.transfer.id }) { item ->
                AnnotatedTransferRow(item, account.currency, onClick = { onNavigateToTransaction(item.transfer.id) })
                HorizontalDivider()
            }
        }
    }
}

/** Kept data-driven so the account-history layout can be verified with fictional data. */
@Composable
internal fun ExtensionLedgerContent(
    account: Account?,
    transfers: List<Transfer>,
    onNavigateToTransaction: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (account == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("找不到帳戶", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val groupedTransfers = transfers.groupBy(::ledgerMonthKey)
    LazyColumn(modifier = modifier) {
        item(key = "account-header") {
            LedgerAccountHeader(account)
        }
        if (account.transferSyncComplete == false) {
            item(key = "partial-history") {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("部分明細尚未同步完成") },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        if (transfers.isEmpty()) {
            item(key = "empty") {
                Box(
                    modifier = Modifier.fillParentMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("尚無交易紀錄", style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else {
            groupedTransfers.forEach { (month, monthTransfers) ->
                stickyHeader(key = "month-$month") {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Text(
                            text = ledgerMonthLabel(month),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(monthTransfers, key = Transfer::id) { transfer ->
                    TransferRow(transfer, account.currency, onClick = { onNavigateToTransaction(transfer.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LedgerAccountHeader(account: Account) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = account.accountName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                account.accountNo?.let { accountNo ->
                    Text(
                        text = maskLedgerAccountNo(accountNo),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = ledgerPrimaryAmountLabel(account.kind),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = formatCurrencyAmount(account.balance, account.currency),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun TransferRow(transfer: Transfer, currency: String, onClick: () -> Unit) {
    val date = ledgerDate(transfer.txnDateTime)
    val isIncome = transfer.amount >= 0
    val amountColor = if (isIncome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.width(50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date.first, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(date.second, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Column(
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = transfer.type ?: "尚未分類",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = transfer.description.ifBlank { "未提供交易說明" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (transfer.memo.isNotBlank()) {
                Text(
                    text = transfer.memo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            transfer.status?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = signedTransferAmount(transfer.amount, currency),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = amountColor,
            )
            transfer.balance?.let { balance ->
                Text(
                    text = "餘額 ${formatCurrencyAmount(balance, currency)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AnnotatedTransferRow(item: TransferListItem, currency: String, onClick: () -> Unit) {
    val transfer = item.transfer
    val date = ledgerDate(transfer.txnDateTime)
    val isIncome = transfer.amount >= 0
    val amountColor = if (isIncome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.width(50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date.first, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(date.second, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.category?.name ?: "尚未分類", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(transfer.description.ifBlank { "未提供交易說明" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (item.tags.isNotEmpty()) Text(item.tags.joinToString(" · ") { "#${it.name}" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            item.annotation?.note?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(signedTransferAmount(transfer.amount, currency), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = amountColor)
            transfer.balance?.let { Text("餘額 ${formatCurrencyAmount(it, currency)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

internal fun ledgerMonthKey(transfer: Transfer): String =
    transfer.txnDateTime.take(7).takeIf { it.matches(Regex("\\d{4}-\\d{2}")) } ?: "other"

internal fun ledgerMonthLabel(month: String): String {
    if (month == "other") return "其他交易"
    return try {
        val date = LocalDate.parse("$month-01")
        "${date.year} 年 ${date.monthValue} 月"
    } catch (_: Exception) {
        "其他交易"
    }
}

internal fun ledgerDate(txnDateTime: String): Pair<String, String> {
    val date = txnDateTime.take(10)
    return try {
        val parsed = LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE)
        parsed.month.getDisplayName(TextStyle.SHORT, Locale.TAIWAN) to parsed.dayOfMonth.toString()
    } catch (_: Exception) {
        "日期" to txnDateTime.take(8)
    }
}

internal fun maskLedgerAccountNo(accountNo: String): String =
    accountNo.takeLast(4).takeIf { accountNo.length > 4 }?.let { "•••• $it" } ?: "••••"

internal fun signedTransferAmount(amount: Double, currency: String): String =
    "${if (amount >= 0) "+" else "-"}${formatCurrencyAmount(abs(amount), currency)}"

internal fun ledgerPrimaryAmountLabel(kind: AssetKind): String = when (kind) {
    AssetKind.DEPOSIT -> "存款餘額"
    AssetKind.TIME_DEPOSIT -> "定存餘額"
    AssetKind.CREDIT_CARD -> "應繳金額"
    AssetKind.LOAN -> "貸款餘額"
}
