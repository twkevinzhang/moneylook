package tw.kevinzhang.extension_runtime.session

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SessionStoreTest {

    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        store = SessionStore(RuntimeEnvironment.getApplication(), Gson())
    }

    @Test
    fun `hasSession returns false before any data stored`() {
        assertFalse(store.hasSession("tw.bot"))
    }

    @Test
    fun `putCookies makes hasSession return true`() {
        store.putCookies("tw.bot", "session=abc")
        assertTrue(store.hasSession("tw.bot"))
    }

    @Test
    fun `getCookies returns stored value`() {
        store.putCookies("tw.bot", "session=abc; token=xyz")
        assertEquals("session=abc; token=xyz", store.getCookies("tw.bot"))
    }

    @Test
    fun `putToken stores token and preserves cookies`() {
        store.putCookies("tw.bot", "session=abc")
        store.putToken("tw.bot", "Authorization", "Bearer tok123")
        assertEquals("Bearer tok123", store.getTokens("tw.bot")["Authorization"])
        assertEquals("session=abc", store.getCookies("tw.bot"))
    }

    @Test
    fun `clearSession removes all data`() {
        store.putCookies("tw.bot", "session=abc")
        store.clearSession("tw.bot")
        assertFalse(store.hasSession("tw.bot"))
        assertNull(store.getCookies("tw.bot"))
    }

    @Test
    fun `different extensions are isolated`() {
        store.putCookies("tw.bot", "bot-cookie")
        store.putCookies("tw.esun", "esun-cookie")
        assertEquals("bot-cookie", store.getCookies("tw.bot"))
        assertEquals("esun-cookie", store.getCookies("tw.esun"))
    }

    @Test
    fun `putToken alone makes hasSession true with null cookies`() {
        store.putToken("tw.bot", "Authorization", "Bearer x")
        assertTrue(store.hasSession("tw.bot"))
        assertNull(store.getCookies("tw.bot"))
    }
}
