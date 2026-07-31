package tw.kevinzhang.moneylook.schedule

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch

/** Launches one bank session without joining it to any other bank's session. */
internal fun <T> CoroutineScope.launchParallelSyncSession(
    completions: SendChannel<T>,
    work: suspend () -> T,
): Job = launch {
    completions.send(work())
}
