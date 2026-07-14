package tw.kevinzhang.moneylook.sync

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.ExtensionRunner
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.login.LoginAutomationConfigParser
import tw.kevinzhang.extension_runtime.login.LoginCredentials
import tw.kevinzhang.extension_runtime.login.NativeLoginRequest
import tw.kevinzhang.extension_runtime.login.NativeLoginResult
import tw.kevinzhang.extension_runtime.login.NativeLoginRunner
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BankSyncCoordinator @Inject constructor(
    private val nativeLoginRunner: NativeLoginRunner,
    private val extensionRunner: ExtensionRunner,
    private val gson: Gson,
) {
    suspend fun sync(
        extension: InstalledExtension,
        profile: CredentialProfile,
    ): SyncResult {
        val config = LoginAutomationConfigParser(gson)
            .parse(extension.loginAutomationJson)
            .getOrElse { return SyncResult.Error(it.message ?: "invalid login automation config") }
        val targetDomains = parseTargetDomains(extension.targetDomainsJson)
            ?: return SyncResult.Error("invalid targetDomains JSON")
        val approvedDomains = parseTargetDomains(profile.approvedDomainsJson)
            ?: return SyncResult.Error("請重新確認擴充功能的網域權限")
        val loginHost = runCatching { URI(extension.loginUrl).host?.lowercase() }.getOrNull()
            ?: return SyncResult.Error("invalid login URL")
        if (loginHost != profile.approvedLoginHost || targetDomains.toSet() != approvedDomains.toSet()) {
            return SyncResult.Error("擴充功能的登入網域權限已變更，請重新儲存帳密以確認")
        }

        return when (
            val login = nativeLoginRunner.login(
                NativeLoginRequest(
                    credentials = LoginCredentials(profile.username, profile.password),
                    config = config,
                    loginUrl = extension.loginUrl,
                    targetDomains = approvedDomains,
                ),
            )
        ) {
            is NativeLoginResult.Error -> SyncResult.Error(login.message)
            is NativeLoginResult.Success -> extensionRunner.run(extension, login.session)
        }
    }

    private fun parseTargetDomains(json: String): List<String>? = try {
        val type = object : TypeToken<List<String>>() {}.type
        gson.fromJson<List<String>>(json, type)?.takeIf(List<String>::isNotEmpty)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
