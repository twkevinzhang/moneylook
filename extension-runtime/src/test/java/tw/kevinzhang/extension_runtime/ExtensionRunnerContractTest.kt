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
import tw.kevinzhang.extension_runtime.data.KindSyncResult
import tw.kevinzhang.extension_runtime.data.KindSyncStatus
import tw.kevinzhang.extension_runtime.data.SyncResult

@RunWith(RobolectricTestRunner::class)
class ExtensionRunnerContractTest {
    private val sourceKey = "a".repeat(64)

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
            ExtensionSyncContext(
                transferCursors = listOf(
                    ExtensionTransferCursor(
                        sourceAccountKey = sourceKey,
                        kind = "deposit",
                        currency = "TWD",
                        latestTxnDateTime = "2026-07-21T18:00:00+08:00",
                    ),
                ),
            ),
        )

        assertTrue(wrapper.contains("credential: deepFreeze({\"username\":\"alice\",\"password\":\"p@ssword\"})"))
        assertTrue(
            wrapper.contains(
                "sync: deepFreeze({\"transferCursors\":[{\"sourceAccountKey\":\"$sourceKey\",\"kind\":\"deposit\",\"currency\":\"TWD\",\"latestTxnDateTime\":\"2026-07-21T18:00:00+08:00\"}]})",
            ),
        )
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
    fun syncContextToStringRedactsCursorValues() {
        val context = ExtensionSyncContext(
            transferCursors = listOf(
                ExtensionTransferCursor(sourceKey, "deposit", "TWD", "2026-07-21T18:00:00+08:00"),
            ),
        )

        assertEquals("ExtensionSyncContext(transferCursors=1)", context.toString())
        assertFalse(context.toString().contains(sourceKey))
        assertFalse(context.toString().contains("2026-07-21"))
        assertEquals("ExtensionTransferCursor([REDACTED])", context.transferCursors.single().toString())
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
            assertEquals(null, transfer.merchantName)
            assertEquals(null, transfer.merchantCategoryCode)
            assertEquals(null, transfer.counterpartyName)
            assertEquals(null, transfer.purpose)
        }
    }

    @Test
    fun `parses range based transfer sync and rejects malformed transfer amounts`() {
        val parsed = parseAccounts(
            """{"accounts":[{"name":"Checking","balance":12.5,"transfers":[
                {"id":"source-1","txnDateTime":"2026-07-21T11:25:30","postingDateTime":"2026-07-22T09:00:00","amount":-2.0,"balance":10.5,"type":"transfer","status":"posted","merchantName":"Fictional Grocery","merchantCategoryCode":"5411","counterpartyName":"Example Counterparty","purpose":"Household supplies"}
            ],"transferSync":{"requestedStart":"2025-07-21","requestedEnd":"2026-07-21",
              "completedRanges":[{"start":"2025-07-21","end":"2026-07-21"}],"complete":true},"sourceAccountKey":"${"a".repeat(64)}"}]}""".trimIndent(),
            Gson(),
        ) as SyncResult.Success
        assertEquals("source-1", parsed.accounts.single().transfers.single().id)
        assertEquals(sourceKey, parsed.accounts.single().sourceAccountKey)
        assertEquals("transfer", parsed.accounts.single().transfers.single().type)
        assertEquals("posted", parsed.accounts.single().transfers.single().status)
        assertEquals("2026-07-22T09:00:00", parsed.accounts.single().transfers.single().postingDateTime)
        assertEquals(10.5, parsed.accounts.single().transfers.single().balance!!, 0.0)
        assertEquals("Fictional Grocery", parsed.accounts.single().transfers.single().merchantName)
        assertEquals("5411", parsed.accounts.single().transfers.single().merchantCategoryCode)
        assertEquals("Example Counterparty", parsed.accounts.single().transfers.single().counterpartyName)
        assertEquals("Household supplies", parsed.accounts.single().transfers.single().purpose)
        assertTrue(parsed.accounts.single().transferSync!!.complete)

        val partialRangeCanStillReturnFetchedTransactions = parseAccounts(
            """{"accounts":[{"name":"Checking","balance":1,"transfers":[
                {"txnDateTime":"2026-06-01","amount":1}
            ],"transferSync":{"requestedStart":"2025-07-21","requestedEnd":"2026-07-21",
              "completedRanges":[{"start":"2026-07-01","end":"2026-07-21"}],"complete":false},"sourceAccountKey":"${"b".repeat(64)}"}]}""".trimIndent(),
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
        val malformedPostingDate = parseAccounts(
            """{"accounts":[{"name":"Card","balance":1,"kind":"credit_card","transfers":[
                {"txnDateTime":"2026-07-21T11:25:30","postingDateTime":"not-a-date","amount":-1}
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
        val legacyHistoryWithoutSourceKey = parseAccounts(
            """{"accounts":[{"name":"Checking","balance":1,"transferSync":{
                "requestedStart":"2025-07-21","requestedEnd":"2026-07-21",
                "completedRanges":[{"start":"2025-07-21","end":"2026-07-21"}],"complete":true
            }}]}""".trimIndent(),
            Gson(),
        )
        val rawAccountNumberAsSourceKey = parseAccounts(
            """{"accounts":[{"name":"Checking","balance":1,"no":"0012345678","sourceAccountKey":"0012345678"}]}""",
            Gson(),
        )
        val nonDigestSourceKey = parseAccounts(
            """{"accounts":[{"name":"Checking","balance":1,"sourceAccountKey":"not-an-opaque-key"}]}""",
            Gson(),
        )
        val sourceKeyEqualToAccountNo = parseAccounts(
            """{"accounts":[{"name":"Checking","balance":1,"no":"${"a".repeat(64)}","sourceAccountKey":"${"a".repeat(64)}"}]}""",
            Gson(),
        )

        assertTrue(missingAmount is SyncResult.Error)
        assertTrue(infiniteRunningBalance is SyncResult.Error)
        assertTrue(malformedPostingDate is SyncResult.Error)
        assertTrue(incompleteMarkedComplete is SyncResult.Error)
        assertTrue(legacyHistoryWithoutSourceKey is SyncResult.Success)
        assertTrue(rawAccountNumberAsSourceKey is SyncResult.Error)
        assertTrue(nonDigestSourceKey is SyncResult.Error)
        assertTrue(sourceKeyEqualToAccountNo is SyncResult.Error)
        assertTrue(partialRangeCanStillReturnFetchedTransactions is SyncResult.Success)
    }

    @Test
    fun `rejects malformed structured classification facts without echoing their values`() {
        val privateValue = "private-value-must-not-escape"
        val malformed = listOf(
            """{"merchantName":""}""",
            """{"merchantName":1}""",
            """{"merchantCategoryCode":"541"}""",
            """{"merchantCategoryCode":"abcd"}""",
            """{"counterpartyName":"   "}""",
            """{"purpose":false}""",
        )

        malformed.forEach { extra ->
            val fields = extra.removePrefix("{").removeSuffix("}")
            val result = parseAccounts(
                """{"accounts":[{"name":"Checking","balance":1,"transfers":[{"txnDateTime":"2026-07-21","amount":-1,$fields,"description":"$privateValue"}]}]}""",
                Gson(),
            )

            assertTrue(result is SyncResult.Error)
            assertFalse((result as SyncResult.Error).message.contains(privateValue))
        }
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
        assertEquals(null, parsed.kindSync)
        parsed.accounts[1].also { card ->
            assertEquals(AssetKind.CREDIT_CARD, card.kind)
            assertEquals("忠孝分行", card.branchName)
            assertEquals(8800.5, card.availableCredit!!, 0.0)
            assertEquals(10000.0, card.creditLimit!!, 0.0)
        }
    }

    @Test
    fun `parses physical cards and maps transfers only by result local ref`() {
        val pan = "4242424242424242" // Explicit fictional Luhn-valid test vector.
        val parsed = parseAccounts(
            """{"accounts":[{"name":"Card statement","balance":1200,"kind":"credit_card",
                "cardsComplete":true,"cards":[
                  {"ref":"main","sourceCardKey":"${"c".repeat(64)}","pan":"$pan","lastFour":"4242","displayName":"Main","network":"Visa","holderRole":"primary","expiryMonth":12,"expiryYear":2030,"creditLimit":10000,"availableCredit":8800},
                  {"ref":"supplementary","maskedPan":"****-****-****-0002","lastFour":"0002","holderRole":"supplementary"}
                ],
                "transfers":[{"txnDateTime":"2026-07-21","amount":-1,"cardRef":"main"}]
            }]}""".trimIndent(),
            Gson(),
        ) as SyncResult.Success

        val account = parsed.accounts.single()
        assertEquals(2, account.cards.size)
        assertEquals(true, account.cardsComplete)
        assertEquals("4242", account.cards.first().lastFour)
        assertEquals("main", account.transfers.single().cardRef)
        assertFalse(account.cards.first().toString().contains(pan))
    }

    @Test
    fun `rejects unsafe or dangling physical card data`() {
        val invalidResults = listOf(
            // Cards are valid only for aggregated credit-card accounts.
            """{"accounts":[{"name":"Deposit","balance":1,"cards":[]}]}""",
            """{"accounts":[{"name":"Deposit","balance":1,"cardsComplete":true}]}""",
            """{"accounts":[{"name":"Card","balance":1,"kind":"credit_card","cardsComplete":true}]}""",
            // Complete card numbers must be 12–19 digits and pass Luhn.
            """{"accounts":[{"name":"Card","balance":1,"kind":"credit_card","cards":[{"ref":"main","pan":"4242424242424241"}]}]}""",
            // A transfer cannot guess an unreturned card.
            """{"accounts":[{"name":"Card","balance":1,"kind":"credit_card","cards":[{"ref":"main"}],"transfers":[{"txnDateTime":"2026-07-21","amount":-1,"cardRef":"unknown"}]}]}""",
            // Sensitive card authentication/magstripe material is never an extension result field.
            """{"accounts":[{"name":"Card","balance":1,"kind":"credit_card","cards":[{"ref":"main","cvv":"123"}]}]}""",
            """{"accounts":[{"name":"Card","balance":1,"kind":"credit_card","cards":[{"ref":"main","holderRole":"owner"}]}]}""",
            """{"accounts":[{"name":"Card","balance":1,"kind":"credit_card","cards":[{"ref":"main","expiryMonth":12}]}]}""",
            """{"accounts":[{"name":"Card","balance":1,"kind":"credit_card","cards":[{"ref":"main","pan":"4242424242424242"},{"ref":"other","pan":"4242424242424242"}]}]}""",
        )

        invalidResults.forEach { json -> assertTrue(parseAccounts(json, Gson()) is SyncResult.Error) }
    }

    @Test
    fun `parses complete and failed per-kind sync results with time deposits`() {
        val parsed = parseAccounts(
            """{"accounts":[
                {"name":"Checking","balance":1,"kind":"deposit"},
                {"name":"Term","balance":2,"kind":"time_deposit"}
            ],"kindSync":[
                {"kind":"deposit","status":"complete"},
                {"kind":"time_deposit","status":"complete"},
                {"kind":"credit_card","status":"failed","code":"NO_PRODUCT"}
            ]}""".trimIndent(),
            Gson(),
        ) as SyncResult.Success

        assertEquals(AssetKind.TIME_DEPOSIT, parsed.accounts[1].kind)
        assertEquals(
            listOf(
                KindSyncResult(AssetKind.DEPOSIT, KindSyncStatus.COMPLETE),
                KindSyncResult(AssetKind.TIME_DEPOSIT, KindSyncStatus.COMPLETE),
                KindSyncResult(AssetKind.CREDIT_CARD, KindSyncStatus.FAILED, "NO_PRODUCT"),
            ),
            parsed.kindSync,
        )
    }

    @Test
    fun `rejects invalid or unsafe per-kind sync results`() {
        val secret = "credential-should-not-escape"
        val invalidResults = listOf(
            // Duplicate kind.
            """{"accounts":[],"kindSync":[{"kind":"deposit","status":"complete"},{"kind":"deposit","status":"failed"}]}""",
            // A failed kind must not return its account.
            """{"accounts":[{"name":"Term","balance":1,"kind":"time_deposit"}],"kindSync":[{"kind":"time_deposit","status":"failed"}]}""",
            // All returned account kinds must have a COMPLETE result.
            """{"accounts":[{"name":"Loan","balance":1,"kind":"loan"}],"kindSync":[{"kind":"deposit","status":"complete"}]}""",
            // At least one kind must have completed.
            """{"accounts":[],"kindSync":[{"kind":"loan","status":"failed","code":"NO_PRODUCT"}]}""",
            // Codes are fixed operational identifiers, not bank messages or payload data.
            """{"accounts":[],"kindSync":[{"kind":"deposit","status":"complete"},{"kind":"loan","status":"failed","code":"$secret"}]}""",
            """{"accounts":[],"kindSync":[{"kind":"deposit","status":"complete"},{"kind":"loan","status":"failed","code":"${"A".repeat(33)}"}]}""",
        )

        invalidResults.forEach { json ->
            val result = parseAccounts(json, Gson()) as SyncResult.Error
            assertFalse(result.message.contains(secret))
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
