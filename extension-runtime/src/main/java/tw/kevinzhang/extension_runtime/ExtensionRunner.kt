package tw.kevinzhang.extension_runtime

import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.data.SyncResult

data class ExtensionCredentials(
    val username: String,
    val password: String,
) {
    override fun toString(): String = "ExtensionCredentials([REDACTED])"
}

interface ExtensionRunner {
    /**
     * Runs the sync-trigger script for the given extension.
     * Credentials are exposed only to this invocation as the frozen `sdk.credentials` object.
     */
    suspend fun run(extension: InstalledExtension, credentials: ExtensionCredentials): SyncResult
}
