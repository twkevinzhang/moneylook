package tw.kevinzhang.moneylook.schedule

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ParallelSyncSessionsTest {
    @Test
    fun `two independent banks overlap before either session is released`() = runTest {
        val completions = Channel<String>(Channel.UNLIMITED)
        val bothStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var started = 0

        launchParallelSyncSession(completions) {
            started += 1
            if (started == 2) bothStarted.complete(Unit)
            release.await()
            "bank-a"
        }
        launchParallelSyncSession(completions) {
            started += 1
            if (started == 2) bothStarted.complete(Unit)
            release.await()
            "bank-b"
        }

        bothStarted.await()
        assertEquals(2, started)
        release.complete(Unit)
        assertEquals(setOf("bank-a", "bank-b"), setOf(completions.receive(), completions.receive()))
    }
}
