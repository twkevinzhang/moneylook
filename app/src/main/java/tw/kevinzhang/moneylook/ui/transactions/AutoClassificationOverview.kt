package tw.kevinzhang.moneylook.ui.transactions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import tw.kevinzhang.core.data.model.AiClassificationPreview
import tw.kevinzhang.core.data.model.ClassificationJob
import tw.kevinzhang.moneylook.ai.AiProvider
import tw.kevinzhang.moneylook.ai.AiSettingsLoadResult

sealed interface AiConfigurationSummary {
    data object Loading : AiConfigurationSummary
    data object Missing : AiConfigurationSummary
    data object CredentialsUnavailable : AiConfigurationSummary
    data class Configured(val providerLabel: String, val model: String) : AiConfigurationSummary
}

enum class AutoClassificationAction { LOADING, OPEN_SETTINGS, START, RESUME, EMPTY, BUSY, BACKGROUND }

data class AutoClassificationOverviewState(
    val configuration: AiConfigurationSummary = AiConfigurationSummary.Loading,
    val preview: AiClassificationPreview? = null,
    val action: AutoClassificationAction = AutoClassificationAction.LOADING,
    val resuming: Boolean = false,
    val background: ClassificationJob? = null,
)

fun AiSettingsLoadResult.toConfigurationSummary(): AiConfigurationSummary = when (this) {
    is AiSettingsLoadResult.Ready -> AiConfigurationSummary.Configured(
        if (settings.provider == AiProvider.CUSTOM) "自訂服務" else settings.provider.label,
        settings.model,
    )
    AiSettingsLoadResult.Missing -> AiConfigurationSummary.Missing
    AiSettingsLoadResult.CredentialsUnavailable -> AiConfigurationSummary.CredentialsUnavailable
}

fun resolveAutoClassificationAction(
    configuration: AiConfigurationSummary,
    preview: AiClassificationPreview?,
    unfinishedSession: Boolean,
    busy: Boolean,
    background: ClassificationJob? = null,
): AutoClassificationAction = when {
    background?.active == true -> AutoClassificationAction.BACKGROUND
    busy -> AutoClassificationAction.BUSY
    configuration == AiConfigurationSummary.Loading || preview == null -> AutoClassificationAction.LOADING
    configuration !is AiConfigurationSummary.Configured -> AutoClassificationAction.OPEN_SETTINGS
    preview.pendingTransferCount == 0 -> AutoClassificationAction.EMPTY
    unfinishedSession || background?.status in setOf("PAUSED", "ERROR") ||
        (preview.firstUpgradePending && preview.upgradeStarted) -> AutoClassificationAction.RESUME
    else -> AutoClassificationAction.START
}

@Composable
fun AutoClassificationOverviewHeader(
    state: AutoClassificationOverviewState,
    onNavigateToAiSettings: () -> Unit,
    onStopBackground: () -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.background?.let { job ->
            if (job.active || job.message != null || job.status in setOf("PAUSED", "ERROR")) {
                Card(Modifier.fillMaxWidth().testTag("auto-classification-background-status")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(when (job.status) {
                            "QUEUED" -> "背景分類已排入佇列"
                            "RESTARTING" -> "規則已變更，正在重新準備背景分類"
                            "STOPPING" -> "正在停止並保存進度"
                            "RUNNING" -> "背景分類中"
                            "PAUSED" -> "背景分類已暫停"
                            "ERROR" -> "背景分類尚未完成"
                            else -> "背景分類進度"
                        }, style = MaterialTheme.typography.titleMedium)
                        Text("已處理 ${job.processed} / ${job.total} 筆交易")
                        job.message?.let { Text(it) }
                        if (job.active) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(onClick = onStopBackground, enabled = job.status != "STOPPING",
                                modifier = Modifier.testTag("auto-classification-stop")) { Text("停止並保存") }
                        }
                    }
                }
            }
        }
        Card(onClick = onNavigateToAiSettings,
            modifier = Modifier.fillMaxWidth().testTag("auto-classification-ai-settings"),
            enabled = state.action != AutoClassificationAction.BUSY,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("AI 分類", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(when (state.configuration) {
                        AiConfigurationSummary.Loading -> "讀取中"
                        AiConfigurationSummary.Missing -> "尚未設定"
                        AiConfigurationSummary.CredentialsUnavailable -> "需要重新設定"
                        is AiConfigurationSummary.Configured -> "已設定"
                    }, style = MaterialTheme.typography.labelLarge)
                    Text("  ›")
                }
                val configuration = state.configuration
                Text(when (configuration) {
                    is AiConfigurationSummary.Configured -> "${configuration.providerLabel} · ${configuration.model}"
                    AiConfigurationSummary.Loading -> "正在讀取手機上的設定"
                    AiConfigurationSummary.Missing -> "選擇服務並輸入 API Token"
                    AiConfigurationSummary.CredentialsUnavailable -> "無法讀取憑證，請重新設定"
                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("系統辨識", style = MaterialTheme.typography.titleSmall)
                Text("自己的帳戶間轉帳 → 不統計／帳戶移轉", style = MaterialTheme.typography.bodyMedium)
                Text("已登入多個銀行帳戶時，對方銀行及完整帳號或末五碼須與另一個已登入帳戶吻合。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun AutoClassificationPrimaryAction(
    state: AutoClassificationOverviewState,
    onOpenSettings: () -> Unit,
    onRequestClassification: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Button(
            onClick = if (state.action == AutoClassificationAction.OPEN_SETTINGS) onOpenSettings else onRequestClassification,
            enabled = state.action in setOf(AutoClassificationAction.OPEN_SETTINGS, AutoClassificationAction.START, AutoClassificationAction.RESUME),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("auto-classification-primary-action"),
        ) {
            Text(when (state.action) {
                AutoClassificationAction.LOADING -> "正在讀取設定…"
                AutoClassificationAction.OPEN_SETTINGS -> "前往 AI分類設定"
                AutoClassificationAction.START -> "開始自動分類"
                AutoClassificationAction.RESUME -> "繼續分類"
                AutoClassificationAction.EMPTY -> "目前沒有待分類交易"
                AutoClassificationAction.BUSY -> "請完成目前操作"
                AutoClassificationAction.BACKGROUND -> "背景分類中"
            })
        }
    }
}
