package tw.kevinzhang.extension_runtime

import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.data.SyncResult

data class ExtensionCredential(
    val json: String,
) {
    override fun toString(): String = "ExtensionCredential([REDACTED])"
}

interface ExtensionRunner {
    /**
     * Runs the sync-trigger script for the given extension.
     * The credential JSON is exposed only to this invocation as the deeply frozen
     * `sdk.credential` object.
     */
    suspend fun run(extension: InstalledExtension, credential: ExtensionCredential): SyncResult
}
