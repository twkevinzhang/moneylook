package tw.kevinzhang.core.data.db

import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.InstalledExtension

@RunWith(RobolectricTestRunner::class)
class InstalledExtensionDaoTest {
    private lateinit var database: MoneylookDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            MoneylookDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `updating installed extension preserves credential profile`() = runBlocking {
        val extension = extension(version = 1)
        database.installedExtensionDao().insert(extension)
        database.credentialProfileDao().upsert(
            CredentialProfile(
                extensionId = extension.id,
                credential = "{\"account\":\"synthetic-user\",\"secret\":\"synthetic-secret\"}",
                scheduleCron = "0 8 * * *",
                timezoneId = "Asia/Taipei",
            ),
        )

        database.installedExtensionDao().insert(extension(version = 2))

        assertEquals(2, database.installedExtensionDao().getById(extension.id)?.version)
        assertNotNull(database.credentialProfileDao().getByExtensionId(extension.id))
        assertEquals(
            "{\"account\":\"synthetic-user\",\"secret\":\"synthetic-secret\"}",
            database.credentialProfileDao().getByExtensionId(extension.id)?.credential,
        )
    }

    @Test
    fun `concurrent installs from different sources keep one manifest owner`() = runBlocking {
        val dao = database.installedExtensionDao()
        val installs = listOf(
            async(Dispatchers.Default) {
                dao.upsertUnlessInstalledFromOtherSource(extension(version = 1))
            },
            async(Dispatchers.Default) {
                dao.upsertUnlessInstalledFromOtherSource(
                    extension(version = 1).copy(
                        id = "ext::other-repo",
                        repoUrl = "https://github.com/test/other-repo",
                    ),
                )
            },
        ).map { it.await() }

        assertEquals(1, installs.count { it })
        assertEquals(1, dao.getAll().count { it.manifestId == "ext" })
    }

    @Test
    fun `install from another source is rejected`() = runBlocking {
        val dao = database.installedExtensionDao()
        assertTrue(dao.upsertUnlessInstalledFromOtherSource(extension(version = 1)))

        val accepted = dao.upsertUnlessInstalledFromOtherSource(
            extension(version = 2).copy(
                id = "ext::other-repo",
                repoUrl = "https://github.com/test/other-repo",
            ),
        )

        assertFalse(accepted)
        assertEquals(listOf("ext::repo"), dao.getAll().map { it.id })
    }

    @Test
    fun `install from same source updates existing extension`() = runBlocking {
        val dao = database.installedExtensionDao()
        assertTrue(dao.upsertUnlessInstalledFromOtherSource(extension(version = 1)))
        assertTrue(dao.upsertUnlessInstalledFromOtherSource(extension(version = 2)))

        assertEquals(2, dao.getById("ext::repo")?.version)
        assertEquals(1, dao.getAll().size)
    }

    private fun extension(version: Int) = InstalledExtension(
        id = "ext::repo",
        manifestId = "ext",
        name = "Bank",
        version = version,
        repoUrl = "https://github.com/test/repo",
        syncTriggerCachePath = "/tmp/sync.js",
        iconUrl = null,
    )
}
