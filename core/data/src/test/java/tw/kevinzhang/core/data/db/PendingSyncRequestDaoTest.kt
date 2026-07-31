package tw.kevinzhang.core.data.db

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.PendingSyncRequest
import tw.kevinzhang.core.data.model.SyncRequestStatus
import tw.kevinzhang.core.data.model.SyncRequestTrigger

@RunWith(RobolectricTestRunner::class)
class PendingSyncRequestDaoTest {
    private lateinit var database: MoneylookDatabase
    private lateinit var dao: PendingSyncRequestDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            MoneylookDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.pendingSyncRequestDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `one extension can have only one queued or running request`() = runBlocking {
        database.installedExtensionDao().insert(extension("a"))
        assertTrue(dao.insertIgnore(request("a")) != -1L)
        assertEquals(-1L, dao.insertIgnore(request("a", SyncRequestTrigger.SCHEDULED)))

        val rows = dao.observeAll().first()
        assertEquals(1, rows.size)
        assertEquals(SyncRequestTrigger.USER, rows.single().trigger)
    }

    @Test
    fun `queued requests become independently running and recover after process death`() = runBlocking {
        database.installedExtensionDao().insert(extension("a"))
        database.installedExtensionDao().insert(extension("b"))
        dao.insertIgnore(request("a"))
        dao.insertIgnore(request("b"))

        assertEquals(1, dao.markRunning("a", 20L))
        assertEquals(SyncRequestStatus.RUNNING, dao.getAll().first { it.extensionId == "a" }.status)
        assertEquals(1, dao.requeueRunning(30L))
        assertEquals(listOf("a", "b"), dao.getQueued().map { it.extensionId })
    }

    @Test
    fun `manual request promotes an unstarted schedule without duplicating its bank session`() = runBlocking {
        database.installedExtensionDao().insert(extension("a"))
        dao.insertIgnore(request("a", SyncRequestTrigger.SCHEDULED))

        assertEquals(-1L, dao.insertIgnore(request("a", SyncRequestTrigger.USER)))
        assertEquals(1, dao.promoteQueuedToUser("a", 20L))
        assertEquals(SyncRequestTrigger.USER, dao.getQueued().single().trigger)

        dao.markRunning("a", 30L)
        assertEquals(1, dao.promoteQueuedToUser("a", 40L))
        assertEquals(SyncRequestTrigger.USER, dao.getByExtensionId("a")?.trigger)
    }

    @Test
    fun `terminal row can be retained safely before cleanup rather than replayed`() = runBlocking {
        database.installedExtensionDao().insert(extension("a"))
        dao.insertIgnore(request("a"))
        dao.markRunning("a", 20L)
        assertEquals(1, dao.markTerminal("a", SyncRequestStatus.PARTIAL, 30L))
        assertTrue(dao.getQueued().isEmpty())
        dao.deleteTerminal()
        assertTrue(dao.getAll().isEmpty())
    }

    private fun request(
        extensionId: String,
        trigger: SyncRequestTrigger = SyncRequestTrigger.USER,
    ) = PendingSyncRequest(extensionId, trigger, requestedAt = 10L)

    private fun extension(id: String) = InstalledExtension(
        id = id,
        manifestId = id,
        name = id,
        version = 1,
        repoUrl = "https://github.com/example/$id",
        syncTriggerCachePath = "/tmp/$id.js",
        iconUrl = null,
        suggestedScheduleCron = null,
        suggestedScheduleTimezone = "Asia/Taipei",
        suggestedScheduleEnabled = false,
        credentialFieldsJson = "[]",
    )
}
