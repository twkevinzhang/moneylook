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
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.extension_runtime.bridge.SafeHttpException
import tw.kevinzhang.extension_runtime.data.SyncResult

@RunWith(RobolectricTestRunner::class)
class ExtensionRunnerContractTest {
    @Test
    fun credentialToStringIsRedacted() {
        val credential = ExtensionCredential("""{"username":"alice","password":"p@ssword"}""")

        assertEquals("ExtensionCredential([REDACTED])", credential.toString())
        assertFalse(credential.toString().contains("alice"))
        assertFalse(credential.toString().contains("p@ssword"))
    }

    @Test
    fun wrapperExposesDeeplyFrozenCredentialAndAsyncPromiseHttpContract() {
        val context = RuntimeEnvironment.getApplication()
        val runner = ExtensionRunnerImpl(context, OkHttpClient(), Gson())

        val wrapper = runner.buildWrappedScript(
            "(async () => ({ accounts: [] }))()",
            ExtensionCredential("""{"username":"alice","password":"p@ssword"}"""),
        )

        assertTrue(wrapper.contains("credential: deepFreeze({\"username\":\"alice\",\"password\":\"p@ssword\"})"))
        assertTrue(wrapper.contains("Object.getOwnPropertyNames(value)"))
        assertFalse(wrapper.contains("credentials:"))
        assertTrue(wrapper.contains("return new Promise"))
        assertTrue(wrapper.contains("const result = await eval"))
        assertTrue(wrapper.contains("allSettled"))
        assertTrue(wrapper.contains("window.fetch = undefined"))
        assertTrue(wrapper.contains("window.XMLHttpRequest = undefined"))
        assertTrue(wrapper.contains("browser: browser"))
        assertTrue(wrapper.contains("open: function(options)"))
        assertTrue(wrapper.contains("post: function(options)"))
        assertTrue(wrapper.contains("request: function(options)"))
        assertTrue(wrapper.contains("close: function()"))
        assertTrue(wrapper.contains("__native_browser__.open"))
        assertTrue(wrapper.contains("__native_browser__.post"))
        assertTrue(wrapper.contains("__native_browser__.request"))
        assertTrue(wrapper.contains("__browserPending.forEach"))
        assertTrue(wrapper.contains("pending.reject({ code: 'BROWSER_CLOSED'"))
        assertTrue(wrapper.contains("__native_browser__.close()"))
        assertTrue(wrapper.contains("finally {\n        browser.close();"))
    }

    @Test
    fun credentialJsonMustBeAFlatStringObject() {
        val runner = ExtensionRunnerImpl(RuntimeEnvironment.getApplication(), OkHttpClient(), Gson())

        assertEquals(
            "{\"customerId\":\"A123\",\"password\":\"secret\"}",
            runner.canonicalCredentialJson("""{"customerId":"A123","password":"secret"}"""),
        )
        assertEquals(null, runner.canonicalCredentialJson("[]"))
        assertEquals(null, runner.canonicalCredentialJson("""{"remember":true}"""))
        assertEquals(null, runner.canonicalCredentialJson("""{"nested":{"value":"x"}}"""))
        assertEquals(null, runner.canonicalCredentialJson("not-json"))
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
        assertEquals(AssetKind.DEPOSIT, parsed.accounts.single().kind)

        val secret = "credential-should-not-escape"
        val invalid = parseAccounts("{invalid:$secret", Gson()) as SyncResult.Error
        assertFalse(invalid.message.contains(secret))
        assertEquals(null, invalid.cause)
    }

    @Test
    fun `legacy transfers accept existing non ISO dates while preserving missing presentation fields`() {
        val parsed = parseAccounts(
            """{"accounts":[{"name":"Checking","balance":12.5,"transfers":[
                {"txnDateTime":"20260721112530","amount":-2.0}
            ]}]}""".trimIndent(),
            Gson(),
        ) as SyncResult.Success

        parsed.accounts.single().transfers.single().also { transfer ->
            assertEquals("20260721112530", transfer.txnDateTime)
            assertEquals("", transfer.description)
            assertEquals("", transfer.memo)
            assertEquals(-2.0, transfer.amount, 0.0)
            assertEquals(null, transfer.balance)
        }
    }

    @Test
    fun `parses range based transfer sync and rejects malformed transfer amounts`() {
        val parsed = parseAccounts(
            """{"accounts":[{"name":"Checking","balance":12.5,"transfers":[
                {"id":"source-1","txnDateTime":"2026-07-21T11:25:30","amount":-2.0,"balance":10.5}
            ],"transferSync":{"requestedStart":"2025-07-21","requestedEnd":"2026-07-21",
              "completedRanges":[{"start":"2025-07-21","end":"2026-07-21"}],"complete":true}}]}""".trimIndent(),
            Gson(),
        ) as SyncResult.Success
        assertEquals("source-1", parsed.accounts.single().transfers.single().id)
        assertEquals(10.5, parsed.accounts.single().transfers.single().balance!!, 0.0)
        assertTrue(parsed.accounts.single().transferSync!!.complete)

        val partialRangeCanStillReturnFetchedTransactions = parseAccounts(
            """{"accounts":[{"name":"Checking","balance":1,"transfers":[
                {"txnDateTime":"2026-06-01","amount":1}
            ],"transferSync":{"requestedStart":"2025-07-21","requestedEnd":"2026-07-21",
              "completedRanges":[{"start":"2026-07-01","end":"2026-07-21"}],"complete":false}}]}""".trimIndent(),
            Gson(),
        )

        val missingAmount = parseAccounts(
            """{"accounts":[{"name":"Checking","balance":1,"transfers":[{"txnDateTime":"20260721"}]}]}""",
            Gson(),
        )
        val infiniteRunningBalance = parseAccounts(
            """{"accounts":[{"name":"Checking","balance":1,"transfers":[
                {"txnDateTime":"20260721","amount":1,"balance":1e999}
            ]}]}""".trimIndent(),
            Gson(),
        )
        val incompleteMarkedComplete = parseAccounts(
            """{"accounts":[{"name":"Checking","balance":1,"transferSync":{
                "requestedStart":"2025-07-21","requestedEnd":"2026-07-21",
                "completedRanges":[{"start":"2026-01-01","end":"2026-07-21"}],"complete":true
            }}]}""".trimIndent(),
            Gson(),
        )

        assertTrue(missingAmount is SyncResult.Error)
        assertTrue(infiniteRunningBalance is SyncResult.Error)
        assertTrue(incompleteMarkedComplete is SyncResult.Error)
        assertTrue(partialRangeCanStillReturnFetchedTransactions is SyncResult.Success)
    }

    @Test
    fun `parses typed card account fields and defaults legacy accounts to deposits`() {
        val parsed = parseAccounts(
            """{"accounts":[
                {"name":"Legacy","balance":0,"currency":"TWD"},
                {"name":"Card","balance":1200.5,"currency":"TWD","no":"1234",
                 "kind":"credit_card","branchName":"忠孝分行",
                 "availableCredit":8800.5,"creditLimit":10000}
            ]}""".trimIndent(),
            Gson(),
        ) as SyncResult.Success

        assertEquals(AssetKind.DEPOSIT, parsed.accounts[0].kind)
        parsed.accounts[1].also { card ->
            assertEquals(AssetKind.CREDIT_CARD, card.kind)
            assertEquals("忠孝分行", card.branchName)
            assertEquals(8800.5, card.availableCredit!!, 0.0)
            assertEquals(10000.0, card.creditLimit!!, 0.0)
        }
    }

    @Test
    fun `rejects invalid asset kinds and non finite balances`() {
        val invalidKind = parseAccounts(
            """{"accounts":[{"name":"Checking","balance":1,"kind":"card"}]}""",
            Gson(),
        )
        val nonFiniteBalance = parseAccounts(
            """{"accounts":[{"name":"Checking","balance":1e999}]}""",
            Gson(),
        )
        val nonFiniteCreditLimit = parseAccounts(
            """{"accounts":[{"name":"Card","balance":1,"kind":"credit_card","creditLimit":1e999}]}""",
            Gson(),
        )
        val negativeLoanBalance = parseAccounts(
            """{"accounts":[{"name":"Loan","balance":-1,"kind":"loan"}]}""",
            Gson(),
        )

        assertTrue(invalidKind is SyncResult.Error)
        assertTrue(nonFiniteBalance is SyncResult.Error)
        assertTrue(nonFiniteCreditLimit is SyncResult.Error)
        assertTrue(negativeLoanBalance is SyncResult.Error)
    }
}
