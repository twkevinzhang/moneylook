package tw.kevinzhang.core.data.db

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.InstalledExtension

@RunWith(RobolectricTestRunner::class)
class CredentialProfileDaoTest {
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
    fun `runnable schedules require enabled schedule and non-empty credential`() = runBlocking {
        listOf(
            profile(extensionId = "runnable", credential = "{}", scheduleEnabled = true),
            profile(extensionId = "empty", credential = "", scheduleEnabled = true),
            profile(extensionId = "disabled", credential = "{}", scheduleEnabled = false),
        ).forEach { profile ->
            database.installedExtensionDao().insert(extension(profile.extensionId))
            database.credentialProfileDao().upsert(profile)
        }

        assertEquals(
            listOf("runnable"),
            database.credentialProfileDao().getRunnableSchedules().map { it.extensionId },
        )
    }

    private fun profile(
        extensionId: String,
        credential: String,
        scheduleEnabled: Boolean,
    ) = CredentialProfile(
        extensionId = extensionId,
        credential = credential,
        scheduleEnabled = scheduleEnabled,
        scheduleCron = "0 8 * * *",
        timezoneId = "Asia/Taipei",
    )

    private fun extension(id: String) = InstalledExtension(
        id = id,
        manifestId = id,
        name = id,
        version = 1,
        repoUrl = "https://github.com/test/repo",
        syncTriggerCachePath = "/tmp/sync.js",
        iconUrl = null,
    )
}
