package tw.kevinzhang.moneylook.ui.marketplace

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.marketplace.MarketplaceRepository
import tw.kevinzhang.marketplace.DownloadedExtensionArtifact
import tw.kevinzhang.marketplace.RepoUrlRepository
import tw.kevinzhang.marketplace.data.ExtensionIndexEntry
import tw.kevinzhang.marketplace.data.ExtensionManifest
import tw.kevinzhang.moneylook.schedule.SchedulerManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MarketplaceViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `install error is exposed and can be cleared after snackbar consumption`() = runTest(dispatcher) {
        val repository = FakeMarketplaceRepository().apply {
            downloadFailure = IllegalStateException("synthetic download failure")
        }
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.install(REPO_URL, ENTRY)
        advanceUntilIdle()

        assertEquals("安裝失敗: synthetic download failure", viewModel.error.value)
        viewModel.clearError()
        assertEquals(null, viewModel.error.value)
    }

    @Test
    fun `install rejects manifest already installed from another source`() = runTest(dispatcher) {
        val installedDao = FakeInstalledExtensionDao(
            installed = listOf(installedExtension(source = "local-adb")),
        )
        val repository = FakeMarketplaceRepository()
        val viewModel = viewModel(repository, installedDao)
        advanceUntilIdle()

        viewModel.install(REPO_URL, ENTRY)
        advanceUntilIdle()

        assertEquals("安裝失敗: 此 Extension 已由其他來源安裝", viewModel.error.value)
        assertEquals(0, repository.downloadCount)
        assertEquals(1, installedDao.getAll().size)
    }

    @Test
    fun `successful install persists immutable artifact identity and content addressed path`() =
        runTest(dispatcher) {
            val repository = FakeMarketplaceRepository()
            val installedDao = FakeInstalledExtensionDao()
            val viewModel = viewModel(repository, installedDao)
            advanceUntilIdle()

            viewModel.install(REPO_URL, ENTRY)
            advanceUntilIdle()

            val installed = installedDao.getAll().single()
            assertEquals("/tmp/artifacts/test-sha256.js", installed.syncTriggerCachePath)
            assertEquals("test-revision", installed.artifactRevision)
            assertEquals("test-sha256", installed.artifactSha256)
        }

    private fun viewModel(
        repository: FakeMarketplaceRepository,
        installedDao: FakeInstalledExtensionDao = FakeInstalledExtensionDao(),
    ) = MarketplaceViewModel(
        marketplaceRepository = repository,
        repoUrlRepository = FakeRepoUrlRepository(),
        installedExtensionDao = installedDao,
        accountDao = FakeAccountDao(),
        schedulerManager = SchedulerManager(RuntimeEnvironment.getApplication()),
        gson = Gson(),
    )

    private class FakeMarketplaceRepository : MarketplaceRepository {
        var downloadFailure: Exception? = null
        var downloadCount = 0

        override suspend fun fetchIndex(repoUrl: String) = listOf(ENTRY)

        override suspend fun fetchManifest(repoUrl: String, path: String) = MANIFEST

        override suspend fun downloadSyncTriggerScript(
            repoUrl: String,
            path: String,
            extensionId: String,
        ): DownloadedExtensionArtifact {
            downloadCount += 1
            downloadFailure?.let { throw it }
            return DownloadedExtensionArtifact(
                "/tmp/artifacts/test-sha256.js",
                "test-revision",
                "test-sha256",
            )
        }
    }

    private class FakeRepoUrlRepository : RepoUrlRepository {
        private val urls = MutableStateFlow(setOf(REPO_URL))

        override fun observeRepoUrls(): Flow<Set<String>> = urls

        override suspend fun addRepoUrl(url: String) {
            urls.value += url
        }

        override suspend fun removeRepoUrl(url: String) {
            urls.value -= url
        }
    }

    private class FakeInstalledExtensionDao(
        installed: List<InstalledExtension> = emptyList(),
    ) : InstalledExtensionDao {
        private val values = MutableStateFlow(installed)

        override fun observeAll(): Flow<List<InstalledExtension>> = values

        override suspend fun getAll(): List<InstalledExtension> = values.value

        override suspend fun getById(id: String): InstalledExtension? = values.value.firstOrNull { it.id == id }

        override suspend fun getByManifestId(manifestId: String): InstalledExtension? =
            values.value.firstOrNull { it.manifestId == manifestId }

        override suspend fun insert(extension: InstalledExtension) {
            values.value = values.value.filterNot { it.id == extension.id } + extension
        }

        override suspend fun deleteById(id: String) {
            values.value = values.value.filterNot { it.id == id }
        }
    }

    private class FakeAccountDao : AccountDao {
        override fun observeAll(): Flow<List<Account>> = MutableStateFlow(emptyList())
        override suspend fun upsertAll(accounts: List<Account>) = Unit
        override suspend fun deleteByExtensionId(extensionId: String) = Unit
    }

    companion object {
        private const val REPO_URL = "https://github.com/test/extensions"
        private val ENTRY = ExtensionIndexEntry(
            id = "tw.test.bank",
            name = "Test Bank",
            version = 2,
            versionName = "2.0.0",
            path = "tw.test.bank",
        )
        private val MANIFEST = ExtensionManifest(
            id = ENTRY.id,
            name = ENTRY.name,
            version = ENTRY.version,
            versionName = ENTRY.versionName,
            description = "Test",
            credential = ExtensionManifest.CredentialConfig(
                listOf(
                    ExtensionManifest.CredentialField(
                        key = "account",
                        label = "帳號",
                        type = "text",
                        required = true,
                        summary = true,
                    ),
                ),
            ),
            syncTrigger = ExtensionManifest.SyncTriggerConfig(),
            schedule = null,
            iconUrl = null,
        )

        private fun installedExtension(source: String) = InstalledExtension(
            id = extensionCompositeId(ENTRY.id, source),
            manifestId = ENTRY.id,
            name = ENTRY.name,
            version = ENTRY.version,
            repoUrl = source,
            syncTriggerCachePath = "/tmp/sync.js",
            iconUrl = null,
        )
    }
}
