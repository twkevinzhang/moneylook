package tw.kevinzhang.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.InstalledExtension

@Database(
    entities = [Account::class, InstalledExtension::class],
    version = 1,
    exportSchema = false,
)
abstract class MoneylookDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun installedExtensionDao(): InstalledExtensionDao
}
