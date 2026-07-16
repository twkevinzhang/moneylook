package tw.kevinzhang.moneylook.ui.marketplace

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.marketplace.data.ExtensionIndexEntry

class MarketplaceStateTest {
    private val repoUrl = "https://github.com/test/extensions"
    private val entry = ExtensionIndexEntry(
        id = "tw.test.bank",
        name = "Test Bank",
        version = 2,
        versionName = "2.0.0",
        path = "tw.test.bank",
    )

    @Test
    fun `exact installed extension shows remove`() {
        assertEquals(
            MarketplaceExtensionAction.REMOVE,
            resolveMarketplaceAction(entry, repoUrl, listOf(installed(repoUrl, version = 2))),
        )
    }

    @Test
    fun `only exact older extension shows update`() {
        assertEquals(
            MarketplaceExtensionAction.UPDATE,
            resolveMarketplaceAction(entry, repoUrl, listOf(installed(repoUrl, version = 1))),
        )
        assertEquals(
            MarketplaceExtensionAction.INSTALLED_FROM_OTHER_SOURCE,
            resolveMarketplaceAction(entry, repoUrl, listOf(installed("local-adb", version = 1))),
        )
    }

    @Test
    fun `same manifest from different source disables install`() {
        assertEquals(
            MarketplaceExtensionAction.INSTALLED_FROM_OTHER_SOURCE,
            resolveMarketplaceAction(entry, repoUrl, listOf(installed("local-adb", version = 2))),
        )
    }

    @Test
    fun `missing manifest shows install`() {
        assertEquals(
            MarketplaceExtensionAction.INSTALL,
            resolveMarketplaceAction(entry, repoUrl, emptyList()),
        )
    }

    private fun installed(source: String, version: Int) = InstalledExtension(
        id = extensionCompositeId(entry.id, source),
        manifestId = entry.id,
        name = entry.name,
        version = version,
        repoUrl = source,
        syncTriggerCachePath = "/tmp/sync.js",
        iconUrl = null,
    )
}
