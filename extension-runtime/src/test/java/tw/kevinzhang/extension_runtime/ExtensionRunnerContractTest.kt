package tw.kevinzhang.extension_runtime

import com.google.gson.Gson
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.extension_runtime.bridge.SafeHttpException
import tw.kevinzhang.extension_runtime.data.SyncResult

@RunWith(RobolectricTestRunner::class)
class ExtensionRunnerContractTest {
    @Test
    fun credentialsToStringIsRedacted() {
        val credentials = ExtensionCredentials("alice", "p@ssword")

        assertEquals("ExtensionCredentials([REDACTED])", credentials.toString())
        assertFalse(credentials.toString().contains("alice"))
        assertFalse(credentials.toString().contains("p@ssword"))
    }

    @Test
    fun wrapperExposesFrozenCredentialsAndAsyncPromiseHttpContract() {
        val context = RuntimeEnvironment.getApplication()
        val runner = ExtensionRunnerImpl(context, OkHttpClient(), Gson())

        val wrapper = runner.buildWrappedScript(
            "(async () => ({ accounts: [] }))()",
            ExtensionCredentials("alice", "p@ssword"),
        )

        assertTrue(wrapper.contains("credentials: Object.freeze({\"username\":\"alice\",\"password\":\"p@ssword\"})"))
        assertTrue(wrapper.contains("return new Promise"))
        assertTrue(wrapper.contains("const result = await eval"))
        assertTrue(wrapper.contains("allSettled"))
        assertTrue(wrapper.contains("window.fetch = undefined"))
        assertTrue(wrapper.contains("window.XMLHttpRequest = undefined"))
    }

    @Test
    fun safeErrorsNeverExposeExceptionContent() {
        val secret = "Authorization: Bearer top-secret; body=private; password=hunter2"

        val rejected = safeBridgeError(SafeHttpException("INVALID_REQUEST", secret))
        val unexpected = safeBridgeError(IllegalStateException(secret))

        assertEquals("INVALID_REQUEST", rejected.code)
        assertFalse(rejected.message.contains("secret"))
        assertFalse(unexpected.message.contains("private"))
        assertFalse(unexpected.message.contains("hunter2"))
    }

    @Test
    fun parsesAccountsAndTransfersWithoutEchoingInvalidPayload() {
        val parsed = parseAccounts(
            """{"accounts":[{"name":"Checking","balance":12.5,"transfers":[{"txnDateTime":"2026-01-01","amount":-2.0}]}]}""",
            Gson(),
        ) as SyncResult.Success
        assertEquals("Checking", parsed.accounts.single().name)
        assertEquals(-2.0, parsed.accounts.single().transfers.single().amount, 0.0)

        val secret = "credential-should-not-escape"
        val invalid = parseAccounts("{invalid:$secret", Gson()) as SyncResult.Error
        assertFalse(invalid.message.contains(secret))
        assertEquals(null, invalid.cause)
    }
}
