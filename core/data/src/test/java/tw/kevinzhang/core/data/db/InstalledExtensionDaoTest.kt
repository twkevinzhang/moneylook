package tw.kevinzhang.core.data.db

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
                username = "user",
                password = "plain-password",
                scheduleCron = "0 8 * * *",
                timezoneId = "Asia/Taipei",
            ),
        )

        database.installedExtensionDao().insert(extension(version = 2))

        assertEquals(2, database.installedExtensionDao().getById(extension.id)?.version)
        assertNotNull(database.credentialProfileDao().getByExtensionId(extension.id))
        assertEquals(
            "plain-password",
            database.credentialProfileDao().getByExtensionId(extension.id)?.password,
        )
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
