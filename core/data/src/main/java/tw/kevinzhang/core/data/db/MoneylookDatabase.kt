package tw.kevinzhang.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.Transfer

@Database(
    entities = [Account::class, InstalledExtension::class, Transfer::class],
    version = 3,
    exportSchema = false,
)
abstract class MoneylookDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun installedExtensionDao(): InstalledExtensionDao
    abstract fun transferDao(): TransferDao
}
