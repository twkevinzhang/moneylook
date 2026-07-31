package tw.kevinzhang.moneylook.schedule

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import tw.kevinzhang.core.data.db.CredentialProfileDao
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.db.PendingSyncRequestDao
import tw.kevinzhang.core.data.model.IngestionTrigger
import tw.kevinzhang.core.data.model.PendingSyncRequest
import tw.kevinzhang.core.data.model.SyncRequestStatus
import tw.kevinzhang.core.data.model.SyncRequestTrigger
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.moneylook.sync.BankSyncCoordinator
import tw.kevinzhang.moneylook.sync.SyncResultPersister
import tw.kevinzhang.moneylook.sync.appLastRunStatus
import tw.kevinzhang.moneylook.sync.hasPartialSyncFailure
import java.util.concurrent.TimeUnit

/**
 * The sole foreground WorkManager boundary for all banks. Requests themselves live in Room so
 * one extension remains deduplicated while distinct extensions run in independent coroutines.
 */
@HiltWorker
class BankSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncCoordinator: BankSyncCoordinator,
    private val syncResultPersister: SyncResultPersister,
    private val installedExtensionDao: InstalledExtensionDao,
    private val credentialProfileDao: CredentialProfileDao,
    private val pendingSyncRequestDao: PendingSyncRequestDao,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!SyncNotification.isAllowed(applicationContext)) {
            // A visible notification is mandatory for this feature. Do not leave a stale primary
            // key that would make a later manual request look like a duplicate after permission
            // has been restored.
            pendingSyncRequestDao.deleteAll()
            return Result.success()
        }
        SyncNotification.ensureChannel(applicationContext)
        setForeground(foregroundInfo(emptyList()))

        return try {
            // A process death can leave rows RUNNING. There cannot be a concurrent global worker
            // because this worker is unique, so re-claiming them is safe and avoids losing work.
            pendingSyncRequestDao.requeueRunning(System.currentTimeMillis())
            pendingSyncRequestDao.deleteTerminal()
            drainRequests()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // No bank session has been deliberately retried here. Room state retains unfinished
            // rows for the bounded WorkManager retry below.
            Result.retry()
        } finally {
            SyncNotification.cancel(applicationContext)
        }
    }

    private suspend fun drainRequests(): Result = supervisorScope {
        val updates = Channel<Unit>(Channel.CONFLATED)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            pendingSyncRequestDao.observeAll().collect { updates.trySend(Unit) }
        }
        val running = linkedMapOf<String, Job>()
        val completions = Channel<Completion>(Channel.UNLIMITED)
        val notificationEntries = linkedMapOf<String, SyncNotificationEntry>()
        var retryNeeded = false

        try {
            while (true) {
                // Claim every currently queued extension. Claims make simultaneous new requests
                // for an already-running bank a no-op, while each different bank starts now.
                pendingSyncRequestDao.getQueued().forEach { request ->
                    if (pendingSyncRequestDao.markRunning(request.extensionId, System.currentTimeMillis()) != 1) {
                        return@forEach
                    }
                    val claimed = pendingSyncRequestDao.getByExtensionId(request.extensionId)
                        ?: return@forEach
                    val name = installedExtensionDao.getById(claimed.extensionId)?.name
                        ?: request.extensionId
                    notificationEntries[claimed.extensionId] = SyncNotificationEntry(
                        claimed.extensionId,
                        name,
                        SyncNotificationStatus.RUNNING,
                    )
                    publish(notificationEntries.values)
                    running[claimed.extensionId] = launchParallelSyncSession(completions) {
                        val result = try {
                            execute(claimed)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            // This worker must never silently strand a RUNNING row. The parent
                            // serializes all progress mutations and will retain it for retry.
                            TerminalResult.Retry
                        }
                        Completion(claimed.extensionId, result)
                    }
                }

                if (running.isEmpty()) {
                    // The Flow subscription is established before this check. A request inserted
                    // during the final empty check wakes this worker; APPEND_OR_REPLACE also
                    // supplies a successor worker for the narrow post-return race.
                    if (pendingSyncRequestDao.getQueued().isEmpty()) break
                    continue
                }
                select<Unit> {
                    updates.onReceive { }
                    completions.onReceive { completion ->
                        running.remove(completion.extensionId)
                        when (val terminal = completion.result) {
                            TerminalResult.Retry -> retryNeeded = true
                            is TerminalResult.Done -> {
                                notificationEntries[completion.extensionId] = notificationEntries
                                    .getValue(completion.extensionId)
                                    .copy(status = terminal.status)
                                publish(notificationEntries.values)
                                // Persist terminal status before removal. If cleanup loses a race
                                // with process death, the next worker removes this row rather than
                                // replaying a completed bank session.
                                pendingSyncRequestDao.markTerminal(
                                    completion.extensionId,
                                    terminal.status.requestStatus,
                                    System.currentTimeMillis(),
                                )
                                pendingSyncRequestDao.deleteByExtensionId(completion.extensionId)
                            }
                        }
                    }
                }
            }
            if (retryNeeded) Result.retry() else Result.success()
        } finally {
            observer.cancel()
            updates.close()
            completions.close()
        }
    }

    private suspend fun execute(request: PendingSyncRequest): TerminalResult {
        val extension = try {
            installedExtensionDao.getById(request.extensionId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return TerminalResult.Retry
        } ?: return TerminalResult.Done(SyncNotificationStatus.SKIPPED)
        val profile = try {
            credentialProfileDao.getByExtensionId(request.extensionId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return TerminalResult.Retry
        } ?: return TerminalResult.Done(SyncNotificationStatus.SKIPPED)

        if (profile.credential.isBlank() ||
            (request.trigger == SyncRequestTrigger.SCHEDULED && !profile.scheduleEnabled)
        ) return TerminalResult.Done(SyncNotificationStatus.SKIPPED)

        val result = try {
            syncCoordinator.sync(extension, profile)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recordFailureSafely(
                extension = extension,
                trigger = request.trigger.ingestionTrigger,
                failure = SyncResult.Error(
                    message = "sync runtime failed",
                    origin = "RUNTIME",
                    cause = error,
                    rawMessage = error.message ?: error.toString(),
                    rawStack = error.stackTraceToString(),
                ),
            )
            updateLastRunErrorSafely(request.extensionId)
            return TerminalResult.Done(SyncNotificationStatus.ERROR)
        }
        return when (result) {
            is SyncResult.Success -> persistSuccess(request, extension, result)
            is SyncResult.Error -> {
                recordFailureSafely(
                    extension,
                    request.trigger.ingestionTrigger,
                    result.sourceDocuments,
                    result.runId,
                    result.runStartedAt,
                    result,
                )
                updateLastRunErrorSafely(request.extensionId)
                TerminalResult.Done(SyncNotificationStatus.ERROR)
            }
        }
    }

    private suspend fun persistSuccess(
        request: PendingSyncRequest,
        extension: tw.kevinzhang.core.data.model.InstalledExtension,
        result: SyncResult.Success,
    ): TerminalResult = try {
        syncResultPersister.persist(extension, result, request.trigger.ingestionTrigger)
        credentialProfileDao.updateLastRun(request.extensionId, System.currentTimeMillis(), result.appLastRunStatus)
        TerminalResult.Done(if (result.hasPartialSyncFailure) SyncNotificationStatus.PARTIAL else SyncNotificationStatus.SUCCESS)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        updateLastRunErrorSafely(request.extensionId)
        TerminalResult.Done(SyncNotificationStatus.ERROR)
    }

    private suspend fun recordFailureSafely(
        extension: tw.kevinzhang.core.data.model.InstalledExtension,
        trigger: IngestionTrigger,
        sourceDocuments: List<tw.kevinzhang.extension_runtime.data.CapturedSourceDocument> = emptyList(),
        sourceRunId: String? = null,
        sourceRunStartedAt: Long? = null,
        failure: SyncResult.Error,
    ) {
        try {
            syncResultPersister.recordFailure(extension, trigger, sourceDocuments, sourceRunId, sourceRunStartedAt, failure)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The terminal app status is still updated below; diagnostics are best effort.
        }
    }

    private suspend fun updateLastRunErrorSafely(extensionId: String) {
        try {
            credentialProfileDao.updateLastRun(extensionId, System.currentTimeMillis(), "error")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The durable request has already been resolved and must not re-login a bank.
        }
    }

    private suspend fun publish(entries: Collection<SyncNotificationEntry>) {
        setForeground(foregroundInfo(entries))
    }

    private fun foregroundInfo(entries: Collection<SyncNotificationEntry>) = ForegroundInfo(
        SyncNotification.NOTIFICATION_ID,
        SyncNotification.create(applicationContext, entries),
        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private sealed interface TerminalResult {
        data object Retry : TerminalResult
        data class Done(val status: SyncNotificationStatus) : TerminalResult
    }

    private data class Completion(val extensionId: String, val result: TerminalResult)

    companion object {
        private const val UNIQUE_QUEUE_NAME = "bank-sync:orchestrator"
        private const val ALL_TAG = "bank-sync:all"

        fun uniqueQueueName(): String = UNIQUE_QUEUE_NAME
        fun allTag(): String = ALL_TAG

        /** Wakes the one global worker without encoding private bank data in WorkManager input. */
        fun wake(context: Context) {
            val request = OneTimeWorkRequestBuilder<BankSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(ALL_TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_QUEUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}

private val SyncRequestTrigger.ingestionTrigger: IngestionTrigger
    get() = when (this) {
        SyncRequestTrigger.USER -> IngestionTrigger.USER_SYNC
        SyncRequestTrigger.SCHEDULED -> IngestionTrigger.SCHEDULED_SYNC
    }

private val SyncNotificationStatus.requestStatus: SyncRequestStatus
    get() = when (this) {
        SyncNotificationStatus.QUEUED -> SyncRequestStatus.QUEUED
        SyncNotificationStatus.RUNNING -> SyncRequestStatus.RUNNING
        SyncNotificationStatus.SUCCESS -> SyncRequestStatus.SUCCESS
        SyncNotificationStatus.PARTIAL -> SyncRequestStatus.PARTIAL
        SyncNotificationStatus.ERROR -> SyncRequestStatus.ERROR
        SyncNotificationStatus.SKIPPED -> SyncRequestStatus.SKIPPED
    }
