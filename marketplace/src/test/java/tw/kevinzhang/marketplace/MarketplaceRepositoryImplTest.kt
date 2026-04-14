package tw.kevinzhang.marketplace

import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MarketplaceRepositoryImplTest {

    private val repo = MarketplaceRepositoryImpl(
        context = RuntimeEnvironment.getApplication(),
        okHttpClient = OkHttpClient(),
        gson = Gson(),
    )

    @Test
    fun `github url converts to raw base`() {
        assertEquals(
            "https://raw.githubusercontent.com/twkevinzhang/moneylook-extensions/main",
            repo.toRawBase("https://github.com/twkevinzhang/moneylook-extensions")
        )
    }

    @Test
    fun `trailing slash is stripped`() {
        assertEquals(
            "https://raw.githubusercontent.com/twkevinzhang/moneylook-extensions/main",
            repo.toRawBase("https://github.com/twkevinzhang/moneylook-extensions/")
        )
    }

    @Test
    fun `already raw url is unchanged`() {
        val rawUrl = "https://raw.githubusercontent.com/owner/repo/main"
        assertEquals(rawUrl, repo.toRawBase(rawUrl))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-github url throws IllegalArgumentException`() {
        repo.toRawBase("https://gitlab.com/owner/repo")
    }
}
