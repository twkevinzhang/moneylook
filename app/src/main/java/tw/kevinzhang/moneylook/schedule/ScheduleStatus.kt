package tw.kevinzhang.moneylook.schedule

sealed class ScheduleStatus {
    /** Extension does not provide a schedule script. */
    object None : ScheduleStatus()

    /** Schedule script exists but no WorkManager job is queued. */
    object Disabled : ScheduleStatus()

    /**
     * A WorkManager job is enqueued.
     * [nextExecMs] is the epoch-millisecond timestamp of the next expected run,
     * computed from the cron expression at the moment the status was observed.
     */
    data class Active(val nextExecMs: Long) : ScheduleStatus()
}
