package tw.kevinzhang.core.data.model

data class AiClassificationPreview(
    val pendingTransferCount: Int,
    val firstUpgradePending: Boolean,
    val upgradeStarted: Boolean,
)
