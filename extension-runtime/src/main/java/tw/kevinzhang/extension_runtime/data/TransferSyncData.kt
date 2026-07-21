package tw.kevinzhang.extension_runtime.data

/**
 * States which portion of an account's history an extension successfully downloaded.
 * Dates are ISO-8601 calendar dates and both ends of a range are inclusive.
 */
data class TransferSyncData(
    val requestedStart: String,
    val requestedEnd: String,
    val completedRanges: List<TransferSyncRangeData>,
    val complete: Boolean,
)

data class TransferSyncRangeData(
    val start: String,
    val end: String,
)
