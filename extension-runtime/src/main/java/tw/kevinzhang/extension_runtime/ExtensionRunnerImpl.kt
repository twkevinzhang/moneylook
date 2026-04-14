package tw.kevinzhang.extension_runtime

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.bridge.HttpBridge
import tw.kevinzhang.extension_runtime.data.AccountData
import tw.kevinzhang.extension_runtime.data.HttpResult
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.session.SessionStore
import java.io.File
import javax.inject.Inject

class ExtensionRunnerImpl @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val sessionStore: SessionStore,
    private val gson: Gson,
) : ExtensionRunner {

    override suspend fun run(extension: InstalledExtension): SyncResult =
        withContext(Dispatchers.IO) {
            // 1. Check session exists
            if (!sessionStore.hasSession(extension.id)) {
                return@withContext SyncResult.Error("session not found — please login first")
            }

            // 2. Load script — validate path stays within filesDir equivalent
            val scriptFile = File(extension.scriptCachePath)
            if (!scriptFile.exists()) {
                return@withContext SyncResult.Error("script file not found: ${extension.scriptCachePath}")
            }
            val script = scriptFile.readText()

            // 3. Parse targetDomains
            val targetDomains: List<String> = try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson(extension.targetDomainsJson, type)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext SyncResult.Error("invalid targetDomains JSON: ${e.message}")
            }

            val bridge = HttpBridge(okHttpClient, sessionStore, extension.id, targetDomains)

            // 4. Run in QuickJS
            runInQuickJs(script, bridge)
        }

    private fun runInQuickJs(script: String, bridge: HttpBridge): SyncResult {
        val context = QuickJSContext.create()
        return try {
            injectSdk(context, bridge)
            // Script must call and return from a top-level IIFE
            // e.g. (function() { ... return { accounts: [...] } })()
            val result = context.evaluate(script)
            parseSyncResult(result, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Error("script error: ${e.message}", cause = e)
        } finally {
            context.destroy()
        }
    }

    /**
     * Injects `sdk.http.get` and `sdk.http.post` into the QuickJS global object.
     * All calls are synchronous (block the QuickJS thread on Dispatchers.IO).
     */
    private fun injectSdk(context: QuickJSContext, bridge: HttpBridge) {
        val global = context.globalObject

        val sdk = context.createNewJSObject()
        val http = context.createNewJSObject()

        http.setProperty("get", JSCallFunction { args ->
            val url = args.getOrNull(0) as? String ?: return@JSCallFunction null
            val headers = (args.getOrNull(1) as? JSObject)?.toStringMap() ?: emptyMap()
            bridge.get(url, headers).toJsObject(context, gson)
        })

        http.setProperty("post", JSCallFunction { args ->
            val url = args.getOrNull(0) as? String ?: return@JSCallFunction null
            val body = args.getOrNull(1) as? String ?: ""
            val headers = (args.getOrNull(2) as? JSObject)?.toStringMap() ?: emptyMap()
            bridge.post(url, body, headers).toJsObject(context, gson)
        })

        sdk.setProperty("http", http)
        global.setProperty("sdk", sdk)

        http.release()
        sdk.release()
        global.release()
    }

    /**
     * Parses the JS return value `{ accounts: [{ name, balance, currency }] }` into SyncResult.
     */
    private fun parseSyncResult(result: Any?, context: QuickJSContext): SyncResult {
        if (result == null) return SyncResult.Error("script returned null")
        if (result !is JSObject) return SyncResult.Error("script must return an object")

        val accountsArray = result.getJSArray("accounts")
            ?: return SyncResult.Error("script result missing 'accounts' array")

        return try {
            val accounts = mutableListOf<AccountData>()
            for (i in 0 until accountsArray.length()) {
                val item = accountsArray.get(i)
                if (item is JSObject) {
                    val name = item.getString("name") ?: continue
                    val balance = item.getDouble("balance") ?: continue
                    val currency = item.getString("currency") ?: "TWD"
                    accounts.add(AccountData(name, balance, currency))
                    item.release()
                }
            }
            SyncResult.Success(accounts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncResult.Error("failed to parse script result: ${e.message}", cause = e)
        } finally {
            accountsArray.release()
            result.release()
        }
    }
}

// Helper: converts JSObject to Map<String, String>
private fun JSObject.toStringMap(): Map<String, String> {
    return toMap()
        .filterValues { it is String }
        .mapValues { it.value as String }
}

// Helper: converts HttpResult to a JSObject for return to JS
private fun HttpResult.toJsObject(context: QuickJSContext, gson: Gson): JSObject {
    val obj = context.createNewJSObject()
    obj.setProperty("status", status)
    obj.setProperty("body", body)
    obj.setProperty("headers", gson.toJson(headers))
    return obj
}
