package tw.kevinzhang.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.Transfer

@Database(
    entities = [Account::class, CredentialProfile::class, InstalledExtension::class, Transfer::class],
    version = 7,
    exportSchema = false,
)
abstract class MoneylookDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun credentialProfileDao(): CredentialProfileDao
    abstract fun installedExtensionDao(): InstalledExtensionDao
    abstract fun transferDao(): TransferDao
}
