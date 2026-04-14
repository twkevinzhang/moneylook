package tw.kevinzhang.marketplace

import org.junit.Assert.assertEquals
import org.junit.Test

class MarketplaceRepositoryImplTest {

    private fun toRawBase(url: String): String {
        val normalized = url.trimEnd('/')
        return if (normalized.contains("raw.githubusercontent.com")) {
            normalized
        } else {
            normalized
                .replace("https://github.com/", "https://raw.githubusercontent.com/")
                .replace("http://github.com/", "https://raw.githubusercontent.com/") + "/main"
        }
    }

    @Test
    fun `github url converts to raw base`() {
        assertEquals(
            "https://raw.githubusercontent.com/twkevinzhang/moneylook-extensions/main",
            toRawBase("https://github.com/twkevinzhang/moneylook-extensions")
        )
    }

    @Test
    fun `trailing slash is stripped`() {
        assertEquals(
            "https://raw.githubusercontent.com/twkevinzhang/moneylook-extensions/main",
            toRawBase("https://github.com/twkevinzhang/moneylook-extensions/")
        )
    }

    @Test
    fun `already raw url is unchanged`() {
        val rawUrl = "https://raw.githubusercontent.com/owner/repo/main"
        assertEquals(rawUrl, toRawBase(rawUrl))
    }
}
