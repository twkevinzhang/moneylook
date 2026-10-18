package tw.kevinzhang.moneylook.ui.transactions

import tw.kevinzhang.core.data.model.AiClassificationPreview
import tw.kevinzhang.moneylook.ai.AiProvider
import tw.kevinzhang.moneylook.ai.AiSettingsLoadResult

sealed interface AiConfigurationSummary {
    data object Loading : AiConfigurationSummary
    data object Missing : AiConfigurationSummary
    data object CredentialsUnavailable : AiConfigurationSummary
    data class Configured(val providerLabel: String, val model: String) : AiConfigurationSummary
}

enum class AutoClassificationAction { LOADING, OPEN_SETTINGS, START, RESUME, EMPTY, BUSY }

data class AutoClassificationOverviewState(
    val configuration: AiConfigurationSummary = AiConfigurationSummary.Loading,
    val preview: AiClassificationPreview? = null,
    val action: AutoClassificationAction = AutoClassificationAction.LOADING,
    val resuming: Boolean = false,
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
): AutoClassificationAction = when {
    busy -> AutoClassificationAction.BUSY
    configuration == AiConfigurationSummary.Loading || preview == null -> AutoClassificationAction.LOADING
    configuration !is AiConfigurationSummary.Configured -> AutoClassificationAction.OPEN_SETTINGS
    preview.pendingTransferCount == 0 -> AutoClassificationAction.EMPTY
    unfinishedSession || (preview.firstUpgradePending && preview.upgradeStarted) -> AutoClassificationAction.RESUME
    else -> AutoClassificationAction.START
}
