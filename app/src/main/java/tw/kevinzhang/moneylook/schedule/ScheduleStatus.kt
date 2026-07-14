package tw.kevinzhang.moneylook.schedule

sealed class ScheduleStatus {
    /** No credential profile has been configured for the extension. */
    object None : ScheduleStatus()

    /** A credential profile exists but its user schedule is disabled or not queued. */
    object Disabled : ScheduleStatus()

    /**
     * A WorkManager job is enqueued.
     * [nextExecMs] is the epoch-millisecond timestamp of the next expected run,
     * computed from the cron expression at the moment the status was observed.
     */
    data class Active(val nextExecMs: Long) : ScheduleStatus()
}
