package tw.kevinzhang.moneylook.sync

import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.ExtensionCredentials
import tw.kevinzhang.extension_runtime.ExtensionRunner
import tw.kevinzhang.extension_runtime.data.SyncResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BankSyncCoordinator @Inject constructor(
    private val extensionRunner: ExtensionRunner,
) {
    suspend fun sync(
        extension: InstalledExtension,
        profile: CredentialProfile,
    ): SyncResult = extensionRunner.run(
        extension = extension,
        credentials = ExtensionCredentials(
            username = profile.username,
            password = profile.password,
        ),
    )
}
