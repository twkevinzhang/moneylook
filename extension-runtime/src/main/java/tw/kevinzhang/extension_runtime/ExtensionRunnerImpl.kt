package tw.kevinzhang.extension_runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.browser.BrowserWebViewSession
import tw.kevinzhang.extension_runtime.browser.NativeSdkBrowserBridge
import tw.kevinzhang.extension_runtime.bridge.HttpRequestJsonParser
import tw.kevinzhang.extension_runtime.bridge.NativeHttpTransport
import tw.kevinzhang.extension_runtime.bridge.RunRequestBudget
import tw.kevinzhang.extension_runtime.bridge.SafeHttpException
import tw.kevinzhang.extension_runtime.data.AccountData
import tw.kevinzhang.extension_runtime.data.CardData
import tw.kevinzhang.extension_runtime.data.KindSyncResult
import tw.kevinzhang.extension_runtime.data.KindSyncStatus
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.data.TransferData
import tw.kevinzhang.extension_runtime.data.TransferSyncData
import tw.kevinzhang.extension_runtime.data.TransferSyncRangeData
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

class ExtensionRunnerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
) : ExtensionRunner {

    override suspend fun run(
        extension: InstalledExtension,
        credential: ExtensionCredential,
        syncContext: ExtensionSyncContext,
    ): SyncResult = withContext(Dispatchers.IO) {
        val scriptFile = File(extension.syncTriggerCachePath)
        if (!scriptFile.isFile) return@withContext SyncResult.Error("extension script file not found")
        if (scriptFile.length() > MAX_SCRIPT_BYTES) {
            return@withContext SyncResult.Error("extension script exceeds size limit")
        }
        val script = try {
            scriptFile.readText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext SyncResult.Error("extension script could not be read")
        }
        val canonicalCredentialJson = canonicalCredentialJson(credential.json)
            ?: return@withContext SyncResult.Error("stored credential JSON is invalid")
        runInWebView(script, ExtensionCredential(canonicalCredentialJson), syncContext)
    }

    private suspend fun runInWebView(
        script: String,
        credential: ExtensionCredential,
        syncContext: ExtensionSyncContext,
    ): SyncResult = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<SyncResult>()
        val webView = WebView(context)
        val requestBudget = RunRequestBudget()
        val httpBridge = NativeSdkHttpBridge(
            webView = webView,
            transport = NativeHttpTransport(okHttpClient),
            requestBudget = requestBudget,
            gson = gson,
        )
        val browserBridge = NativeSdkBrowserBridge(
            controllerWebView = webView,
            session = BrowserWebViewSession(context, gson),
            requestBudget = requestBudget,
            gson = gson,
        )
        @Suppress("SetJavaScriptEnabled")
        webView.settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.addJavascriptInterface(httpBridge, "__native_http__")
        webView.addJavascriptInterface(browserBridge, "__native_browser__")
        webView.addJavascriptInterface(ScriptResultBridge(deferred, gson), "__result_bridge__")
        val evaluated = AtomicBoolean(false)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            @Deprecated("Deprecated in Android")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

            override fun onPageFinished(view: WebView, url: String) {
                if (evaluated.compareAndSet(false, true)) {
                    view.evaluateJavascript(buildWrappedScript(script, credential, syncContext), null)
                }
            }
        }
        webView.loadData("<html><body></body></html>", "text/html", "utf-8")
        try {
            withTimeout(SCRIPT_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            SyncResult.Error("extension script timed out")
        } finally {
            browserBridge.cancel()
            httpBridge.cancel()
            webView.removeJavascriptInterface("__native_http__")
            webView.removeJavascriptInterface("__native_browser__")
            webView.removeJavascriptInterface("__result_bridge__")
            webView.stopLoading()
            webView.destroy()
        }
    }

    internal fun buildWrappedScript(
        script: String,
        credential: ExtensionCredential,
        syncContext: ExtensionSyncContext,
    ): String {
        val scriptLiteral = gson.toJson(script)
        val credentialLiteral = requireNotNull(canonicalCredentialJson(credential.json)) {
            "stored credential JSON is invalid"
        }
        val syncContextLiteral = gson.toJson(syncContext)
        return """
            (async function() {
                'use strict';
                window.fetch = undefined;
                window.XMLHttpRequest = undefined;
                const __pending = new Map();
                let __nextRequestId = 1;
                window.__sdkResolve = function(id, payloadJson, errorJson) {
                    const pending = __pending.get(id);
                    if (!pending) return;
                    __pending.delete(id);
                    if (errorJson !== null) pending.reject(JSON.parse(errorJson));
                    else pending.resolve(JSON.parse(payloadJson));
                };
                const request = function(options) {
                    return new Promise(function(resolve, reject) {
                        const id = String(__nextRequestId++);
                        __pending.set(id, { resolve: resolve, reject: reject });
                        try {
                            __native_http__.request(id, JSON.stringify(options || {}));
                        } catch (_) {
                            __pending.delete(id);
                            reject({ code: 'BRIDGE_ERROR', message: 'native HTTP bridge failed' });
                        }
                    });
                };
                const http = Object.freeze({
                    request: request,
                    all: function(requests) { return Promise.all(requests.map(request)); },
                    allSettled: function(requests) {
                        return Promise.all(requests.map(function(item) {
                            return request(item).then(
                                function(value) { return { status: 'fulfilled', value: value }; },
                                function(reason) { return { status: 'rejected', reason: reason }; }
                            );
                        }));
                    }
                });
                const __browserPending = new Map();
                let __nextBrowserRequestId = 1;
                window.__sdkBrowserResolve = function(id, payloadJson, errorJson) {
                    const pending = __browserPending.get(id);
                    if (!pending) return;
                    __browserPending.delete(id);
                    if (errorJson !== null) pending.reject(JSON.parse(errorJson));
                    else pending.resolve(JSON.parse(payloadJson));
                };
                const browserCall = function(method, options) {
                    return new Promise(function(resolve, reject) {
                        const id = String(__nextBrowserRequestId++);
                        __browserPending.set(id, { resolve: resolve, reject: reject });
                        try {
                            if (method === 'open') {
                                __native_browser__.open(id, JSON.stringify(options || {}));
                            } else if (method === 'post') {
                                __native_browser__.post(id, JSON.stringify(options || {}));
                            } else {
                                __native_browser__.request(id, JSON.stringify(options || {}));
                            }
                        } catch (_) {
                            __browserPending.delete(id);
                            reject({ code: 'BRIDGE_ERROR', message: 'native browser bridge failed' });
                        }
                    });
                };
                const browser = Object.freeze({
                    open: function(options) { return browserCall('open', options); },
                    post: function(options) { return browserCall('post', options); },
                    request: function(options) { return browserCall('request', options); },
                    close: function() {
                        __browserPending.forEach(function(pending) {
                            pending.reject({ code: 'BROWSER_CLOSED', message: 'browser session closed' });
                        });
                        __browserPending.clear();
                        __native_browser__.close();
                    }
                });
                const deepFreeze = function(value) {
                    if (value === null || typeof value !== 'object' || Object.isFrozen(value)) return value;
                    Object.getOwnPropertyNames(value).forEach(function(name) {
                        deepFreeze(value[name]);
                    });
                    return Object.freeze(value);
                };
                const sdk = Object.freeze({
                    credential: deepFreeze($credentialLiteral),
                    sync: deepFreeze($syncContextLiteral),
                    http: http,
                    browser: browser
                });
                try {
                    const result = await eval($scriptLiteral);
                    __result_bridge__.onResult(JSON.stringify(result));
                } catch (error) {
                    const candidate = typeof error === 'object' && error && typeof error.code === 'string'
                        ? error.code
                        : (typeof error === 'object' && error && typeof error.message === 'string'
                            ? error.message : null);
                    const code = typeof candidate === 'string' && /^[A-Z][A-Z0-9_]{0,47}$/.test(candidate)
                        ? candidate : null;
                    const stack = typeof error === 'object' && error && typeof error.stack === 'string' ? error.stack : '';
                    const match = stack.match(/:(\\d+):(\\d+)/);
                    const frame = match ? ('line ' + match[1] + ', column ' + match[2]) : null;
                    __result_bridge__.onError(JSON.stringify({ code: code, frame: frame }));
                } finally {
                    browser.close();
                }
            })();
        """.trimIndent()
    }

    internal fun canonicalCredentialJson(json: String): String? {
        return try {
            val element = JsonParser.parseString(json)
            if (!element.isJsonObject) return null
            val credential = element.asJsonObject
            if (credential.entrySet().any { (_, value) -> !value.isJsonPrimitive || !value.asJsonPrimitive.isString }) {
                return null
            }
            gson.toJson(credential)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val MAX_SCRIPT_BYTES = 2L * 1024 * 1024
        const val SCRIPT_TIMEOUT_MS = 60_000L
    }
}

private class NativeSdkHttpBridge(
    private val webView: WebView,
    private val transport: NativeHttpTransport,
    private val requestBudget: RunRequestBudget,
    private val gson: Gson,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(true)

    @JavascriptInterface
    fun request(id: String, requestJson: String) {
        if (!active.get()) return
        try {
            requestBudget.acquire()
            if (requestJson.length > MAX_REQUEST_JSON_CHARS) {
                throw SafeHttpException("REQUEST_TOO_LARGE", "HTTP request rejected")
            }
        } catch (e: Exception) {
            postResult(id, null, gson.toJson(safeBridgeError(e)))
            return
        }
        scope.launch {
            try {
                val request = HttpRequestJsonParser.parse(requestJson, gson)
                val response = transport.execute(request)
                postResult(id, gson.toJson(response), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                postResult(id, null, gson.toJson(safeBridgeError(e)))
            }
        }
    }

    fun cancel() {
        active.set(false)
        scope.cancel()
    }

    private fun postResult(id: String, resultJson: String?, errorJson: String?) {
        val idLiteral = gson.toJson(id)
        val resultLiteral = resultJson?.let(gson::toJson) ?: "null"
        val errorLiteral = errorJson?.let(gson::toJson) ?: "null"
        mainHandler.post {
            if (active.get()) {
                webView.evaluateJavascript(
                    "window.__sdkResolve($idLiteral,$resultLiteral,$errorLiteral);",
                    null,
                )
            }
        }
    }

    private companion object {
        const val MAX_REQUEST_JSON_CHARS = 4 * 1024 * 1024
    }
}

internal data class SafeBridgeError(val code: String, val message: String)

internal fun safeBridgeError(error: Exception): SafeBridgeError = when (error) {
    is SafeHttpException -> SafeBridgeError(error.code, "HTTP request rejected")
    else -> SafeBridgeError("HTTP_ERROR", "HTTP request failed")
}

private class ScriptResultBridge(
    private val deferred: CompletableDeferred<SyncResult>,
    private val gson: Gson,
) {
    @JavascriptInterface
    fun onResult(json: String) {
        deferred.complete(parseAccounts(json, gson) ?: SyncResult.Error("script result missing 'accounts'"))
    }

    @JavascriptInterface
    fun onError(diagnosticJson: String) {
        // Extension exceptions may embed credentials, request headers, or response bodies.
        val values = runCatching {
            @Suppress("UNCHECKED_CAST") gson.fromJson(diagnosticJson, Map::class.java) as Map<String, Any?>
        }.getOrNull().orEmpty()
        val code = (values["code"] as? String)?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,47}")) }
        val frame = (values["frame"] as? String)?.takeIf { it.matches(Regex("line \\d{1,6}, column \\d{1,6}")) }
        deferred.complete(SyncResult.Error("extension script failed", code = code, scriptFrame = frame))
    }
}

internal fun parseAccounts(json: String, gson: Gson): SyncResult? = try {
    val type = object : TypeToken<Map<String, Any>>() {}.type
    val map: Map<String, Any> = gson.fromJson(json, type)
    val rawList = map["accounts"] as? List<*> ?: return null
    val accounts = rawList.map { item ->
        val account = item as? Map<*, *> ?: return SyncResult.Error("script result contains invalid account")
        val name = account["name"] as? String
            ?: return SyncResult.Error("script result contains invalid account")
        val balance = finiteNumber(account["balance"])
            ?: return SyncResult.Error("script result contains invalid account balance")
        val currency = account["currency"] as? String ?: "TWD"
        val kind = (if ("kind" in account) parseAssetKind(account["kind"]) else AssetKind.DEPOSIT)
            ?: return SyncResult.Error("script result contains invalid account kind")
        val no = optionalString(account, "no")
            ?: return SyncResult.Error("script result contains invalid account")
        val sourceAccountKey = optionalNonBlankString(account, "sourceAccountKey")
            ?: return SyncResult.Error("script result contains invalid source account key")
        if (sourceAccountKey.value != null &&
            (!SOURCE_ACCOUNT_KEY_PATTERN.matches(sourceAccountKey.value) || sourceAccountKey.value == no.value)
        ) {
            return SyncResult.Error("script result contains invalid source account key")
        }
        val branchName = optionalString(account, "branchName")
            ?: return SyncResult.Error("script result contains invalid account")
        val availableCredit = optionalFiniteNumber(account, "availableCredit")
            ?: return SyncResult.Error("script result contains invalid available credit")
        val creditLimit = optionalFiniteNumber(account, "creditLimit")
            ?: return SyncResult.Error("script result contains invalid credit limit")
        if (kind != AssetKind.CREDIT_CARD && (availableCredit.value != null || creditLimit.value != null)) {
            return SyncResult.Error("script result contains credit fields for a non-card account")
        }
        val cards = if ("cards" in account) {
            if (kind != AssetKind.CREDIT_CARD) {
                return SyncResult.Error("script result contains cards for a non-card account")
            }
            parseCards(account) ?: return SyncResult.Error("script result contains invalid card")
        } else {
            emptyList()
        }
        val cardsComplete = when {
            "cardsComplete" !in account -> null
            kind != AssetKind.CREDIT_CARD -> {
                return SyncResult.Error("script result contains card status for a non-card account")
            }
            "cards" !in account -> return SyncResult.Error("script result contains card status without cards")
            account["cardsComplete"] is Boolean -> account["cardsComplete"] as Boolean
            else -> return SyncResult.Error("script result contains invalid card status")
        }
        if (kind != AssetKind.DEPOSIT && balance < 0) {
            return SyncResult.Error("script result contains invalid debt balance")
        }

        val transferSync = if ("transferSync" in account) {
            parseTransferSync(account) ?: return SyncResult.Error("script result contains invalid transfer sync")
        } else {
            null
        }
        val transfers = parseTransfers(account, requireIsoDate = transferSync != null, cards = cards)
            ?: return SyncResult.Error("script result contains invalid transfer")
        if (transferSync != null && transfers.any { transfer ->
                !transferDateInRequestedRange(transfer.txnDateTime, transferSync)
            }
        ) {
            return SyncResult.Error("script result contains transfer outside requested range")
        }
        AccountData(
            name = name,
            balance = balance,
            currency = currency,
            no = no.value,
            sourceAccountKey = sourceAccountKey.value,
            kind = kind,
            branchName = branchName.value,
            availableCredit = availableCredit.value,
            creditLimit = creditLimit.value,
            cards = cards,
            cardsComplete = cardsComplete,
            transfers = transfers,
            transferSync = transferSync,
        )
    }
    val kindSync = if ("kindSync" in map) {
        parseKindSync(map, accounts) ?: return SyncResult.Error("script result contains invalid kind sync")
    } else {
        null
    }
    val cursorIdentities = accounts.mapNotNull { account ->
        account.sourceAccountKey?.let { key -> listOf(key, account.kind.name, account.currency) }
    }
    if (cursorIdentities.size != cursorIdentities.distinct().size) {
        SyncResult.Error("script result contains duplicate source account key")
    } else {
        SyncResult.Success(accounts, kindSync)
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    SyncResult.Error("failed to parse script result")
}

private data class OptionalValue<T>(val value: T?)

/** Source keys are deliberately constrained so raw account/card numbers cannot enter sdk.sync. */
private val SOURCE_ACCOUNT_KEY_PATTERN = Regex("[0-9a-f]{64}")
private val KIND_SYNC_CODE_PATTERN = Regex("[A-Z0-9_]{1,32}")
private val CARD_REF_PATTERN = Regex("[A-Za-z][A-Za-z0-9_.:-]{0,127}")
private val LAST_FOUR_PATTERN = Regex("[0-9]{4}")
private val MERCHANT_CATEGORY_CODE_PATTERN = Regex("[0-9]{4}")

private fun parseKindSync(
    root: Map<String, Any>,
    accounts: List<AccountData>,
): List<KindSyncResult>? {
    val rawResults = root["kindSync"] as? List<*> ?: return null
    val results = rawResults.map { raw ->
        val result = raw as? Map<*, *> ?: return null
        val kind = parseAssetKind(result["kind"]) ?: return null
        val status = parseKindSyncStatus(result["status"]) ?: return null
        val code = when {
            "code" !in result -> null
            result["code"] is String && KIND_SYNC_CODE_PATTERN.matches(result["code"] as String) -> result["code"] as String
            else -> return null
        }
        KindSyncResult(kind, status, code)
    }
    if (results.map { it.kind }.distinct().size != results.size) return null
    if (results.none { it.status == KindSyncStatus.COMPLETE }) return null
    val completeKinds = results
        .filter { it.status == KindSyncStatus.COMPLETE }
        .map { it.kind }
        .toSet()
    if (accounts.any { it.kind !in completeKinds }) return null
    return results
}

private fun parseKindSyncStatus(value: Any?): KindSyncStatus? = when (value) {
    "complete" -> KindSyncStatus.COMPLETE
    "failed" -> KindSyncStatus.FAILED
    "not_applicable" -> KindSyncStatus.NOT_APPLICABLE
    else -> null
}

private fun optionalString(account: Map<*, *>, key: String): OptionalValue<String>? = when {
    key !in account -> OptionalValue(null)
    account[key] is String -> OptionalValue(account[key] as String)
    else -> null
}

private fun optionalNonBlankString(account: Map<*, *>, key: String): OptionalValue<String>? = when {
    key !in account -> OptionalValue(null)
    account[key] is String && (account[key] as String).isNotBlank() -> OptionalValue(account[key] as String)
    else -> null
}

private fun optionalFiniteNumber(account: Map<*, *>, key: String): OptionalValue<Double>? = when {
    key !in account -> OptionalValue(null)
    else -> finiteNumber(account[key])?.let(::OptionalValue)
}

private fun optionalInt(account: Map<*, *>, key: String): OptionalValue<Int>? = when {
    key !in account -> OptionalValue(null)
    else -> finiteNumber(account[key])
        ?.takeIf { it == it.toInt().toDouble() }
        ?.toInt()
        ?.let(::OptionalValue)
}

private fun finiteNumber(value: Any?): Double? =
    (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

private fun parseTransfers(
    account: Map<*, *>,
    requireIsoDate: Boolean,
    cards: List<CardData>,
): List<TransferData>? {
    if ("transfers" !in account) return emptyList()
    val rawTransfers = account["transfers"] as? List<*> ?: return null
    return rawTransfers.map { rawTransfer ->
        val transfer = rawTransfer as? Map<*, *> ?: return null
        val txnDateTime = (transfer["txnDateTime"] as? String)
            ?.takeIf { it.isNotBlank() && (!requireIsoDate || parseTransferDate(it) != null) }
            ?: return null
        val description = optionalString(transfer, "description") ?: return null
        val memo = optionalString(transfer, "memo") ?: return null
        val amount = finiteNumber(transfer["amount"]) ?: return null
        val balance = optionalFiniteNumber(transfer, "balance") ?: return null
        val id = optionalString(transfer, "id")?.value
        if (id != null && id.isBlank()) return null
        val type = optionalNonBlankString(transfer, "type") ?: return null
        val status = optionalNonBlankString(transfer, "status") ?: return null
        val postingDateTime = optionalNonBlankString(transfer, "postingDateTime") ?: return null
        if (postingDateTime.value != null && parseTransferDate(postingDateTime.value) == null) {
            return null
        }
        val cardRef = optionalNonBlankString(transfer, "cardRef") ?: return null
        if (cardRef.value != null && cardRef.value !in cards.map(CardData::ref).toSet()) return null
        val merchantName = optionalNonBlankString(transfer, "merchantName") ?: return null
        val merchantCategoryCode = optionalNonBlankString(transfer, "merchantCategoryCode") ?: return null
        if (merchantCategoryCode.value != null &&
            !MERCHANT_CATEGORY_CODE_PATTERN.matches(merchantCategoryCode.value)
        ) {
            return null
        }
        val counterpartyName = optionalNonBlankString(transfer, "counterpartyName") ?: return null
        val purpose = optionalNonBlankString(transfer, "purpose") ?: return null
        TransferData(
            txnDateTime = txnDateTime,
            description = description.value ?: "",
            amount = amount,
            balance = balance.value,
            memo = memo.value ?: "",
            type = type.value,
            status = status.value,
            id = id,
            postingDateTime = postingDateTime.value,
            cardRef = cardRef.value,
            merchantName = merchantName.value,
            merchantCategoryCode = merchantCategoryCode.value,
            counterpartyName = counterpartyName.value,
            purpose = purpose.value,
        )
    }
}

private fun parseCards(account: Map<*, *>): List<CardData>? {
    val rawCards = account["cards"] as? List<*> ?: return null
    val cards = rawCards.map { rawCard ->
        val card = rawCard as? Map<*, *> ?: return null
        if (card.keys.any { key -> isForbiddenCardField(key as? String) }) return null
        val ref = (card["ref"] as? String)?.takeIf(CARD_REF_PATTERN::matches) ?: return null
        val sourceCardKey = optionalNonBlankString(card, "sourceCardKey") ?: return null
        if (sourceCardKey.value != null && !SOURCE_ACCOUNT_KEY_PATTERN.matches(sourceCardKey.value)) return null
        val pan = optionalNonBlankString(card, "pan") ?: return null
        if (pan.value != null && !isValidPan(pan.value)) return null
        val maskedPan = optionalNonBlankString(card, "maskedPan") ?: return null
        if (maskedPan.value != null && maskedPan.value.all(Char::isDigit)) return null
        val lastFour = optionalNonBlankString(card, "lastFour") ?: return null
        if (lastFour.value != null && !LAST_FOUR_PATTERN.matches(lastFour.value)) return null
        if (pan.value != null && lastFour.value != null && !pan.value.endsWith(lastFour.value)) return null
        val displayName = optionalNonBlankString(card, "displayName") ?: return null
        val network = optionalNonBlankString(card, "network") ?: return null
        val productType = optionalNonBlankString(card, "productType") ?: return null
        val holderRole = optionalNonBlankString(card, "holderRole") ?: return null
        if (holderRole.value != null && holderRole.value !in setOf("primary", "supplementary")) return null
        val holderName = optionalNonBlankString(card, "holderName") ?: return null
        val status = optionalNonBlankString(card, "status") ?: return null
        val expiryMonth = optionalInt(card, "expiryMonth") ?: return null
        val expiryYear = optionalInt(card, "expiryYear") ?: return null
        if ((expiryMonth.value == null) != (expiryYear.value == null)) return null
        if (expiryMonth.value != null && expiryMonth.value !in 1..12) return null
        if (expiryYear.value != null && expiryYear.value !in 2000..2200) return null
        val creditLimit = optionalFiniteNumber(card, "creditLimit") ?: return null
        val availableCredit = optionalFiniteNumber(card, "availableCredit") ?: return null
        if (creditLimit.value != null && creditLimit.value < 0.0) return null
        if (availableCredit.value != null && availableCredit.value < 0.0) return null
        CardData(
            ref = ref,
            sourceCardKey = sourceCardKey.value,
            pan = pan.value,
            maskedPan = maskedPan.value,
            lastFour = lastFour.value,
            displayName = displayName.value,
            network = network.value,
            productType = productType.value,
            holderRole = holderRole.value,
            holderName = holderName.value,
            status = status.value,
            expiryMonth = expiryMonth.value,
            expiryYear = expiryYear.value,
            creditLimit = creditLimit.value,
            availableCredit = availableCredit.value,
        )
    }
    val uniqueRefs = cards.map(CardData::ref).distinct().size == cards.size
    val sourceKeys = cards.mapNotNull(CardData::sourceCardKey)
    val uniqueSourceKeys = sourceKeys.distinct().size == sourceKeys.size
    val pans = cards.mapNotNull(CardData::pan)
    val uniquePans = pans.distinct().size == pans.size
    return cards.takeIf { uniqueRefs && uniqueSourceKeys && uniquePans }
}

private fun isForbiddenCardField(key: String?): Boolean {
    val normalized = key?.lowercase() ?: return true
    return listOf("cvv", "cvc", "pin", "track", "magstripe", "securitycode")
        .any(normalized::contains)
}

private fun isValidPan(value: String): Boolean {
    if (value.length !in 12..19 || !value.all(Char::isDigit)) return false
    val sum = value.reversed().mapIndexed { index, character ->
        val digit = character.digitToInt()
        if (index % 2 == 1) (digit * 2).let { if (it > 9) it - 9 else it } else digit
    }.sum()
    return sum % 10 == 0
}

private fun parseTransferSync(account: Map<*, *>): TransferSyncData? {
    val rawSync = account["transferSync"] as? Map<*, *> ?: return null
    val requestedStart = rawSync["requestedStart"] as? String ?: return null
    val requestedEnd = rawSync["requestedEnd"] as? String ?: return null
    val requestedStartDate = parseCalendarDate(requestedStart) ?: return null
    val requestedEndDate = parseCalendarDate(requestedEnd) ?: return null
    if (requestedStartDate > requestedEndDate) return null
    val complete = rawSync["complete"] as? Boolean ?: return null
    val rawRanges = rawSync["completedRanges"] as? List<*> ?: return null
    val completedRanges = rawRanges.map { rawRange ->
        val range = rawRange as? Map<*, *> ?: return null
        val start = range["start"] as? String ?: return null
        val end = range["end"] as? String ?: return null
        val startDate = parseCalendarDate(start) ?: return null
        val endDate = parseCalendarDate(end) ?: return null
        if (startDate > endDate || startDate < requestedStartDate || endDate > requestedEndDate) return null
        TransferSyncRangeData(start, end)
    }
    if (complete && !rangesCoverRequest(completedRanges, requestedStartDate, requestedEndDate)) return null
    return TransferSyncData(requestedStart, requestedEnd, completedRanges, complete)
}

private fun parseTransferDate(value: String): LocalDate? =
    value.take(10).takeIf { it.length == 10 }?.let(::parseCalendarDate)

private fun parseCalendarDate(value: String): LocalDate? = try {
    LocalDate.parse(value)
} catch (_: Exception) {
    null
}

private fun transferDateInRequestedRange(
    txnDateTime: String,
    sync: TransferSyncData,
): Boolean {
    val date = parseTransferDate(txnDateTime) ?: return false
    val start = parseCalendarDate(sync.requestedStart) ?: return false
    val end = parseCalendarDate(sync.requestedEnd) ?: return false
    return date in start..end
}

private fun rangesCoverRequest(
    ranges: List<TransferSyncRangeData>,
    requestedStart: LocalDate,
    requestedEnd: LocalDate,
): Boolean {
    var nextRequired = requestedStart
    ranges.sortedBy { it.start }.forEach { range ->
        val start = parseCalendarDate(range.start) ?: return false
        val end = parseCalendarDate(range.end) ?: return false
        if (start > nextRequired) return false
        if (end >= requestedEnd) return true
        if (end >= nextRequired) nextRequired = end.plusDays(1)
    }
    return false
}

private fun parseAssetKind(value: Any?): AssetKind? = when (value) {
    "deposit" -> AssetKind.DEPOSIT
    "time_deposit" -> AssetKind.TIME_DEPOSIT
    "credit_card" -> AssetKind.CREDIT_CARD
    "loan" -> AssetKind.LOAN
    else -> null
}
