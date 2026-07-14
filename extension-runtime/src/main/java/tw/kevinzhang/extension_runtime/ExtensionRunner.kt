package tw.kevinzhang.extension_runtime

import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.session.EphemeralSession

interface ExtensionRunner {
    /**
     * Runs the sync-trigger script for the given extension.
     * The native login flow must supply a fresh, in-memory session for this invocation.
     * Session contents are injected by the HTTP proxy and are never exposed to JavaScript.
     */
    suspend fun run(extension: InstalledExtension, session: EphemeralSession): SyncResult
}
