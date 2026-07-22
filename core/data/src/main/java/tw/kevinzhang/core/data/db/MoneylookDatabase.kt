package tw.kevinzhang.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleTagCrossRef
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.TransferAnnotation
import tw.kevinzhang.core.data.model.TransferTagCrossRef

@Database(
    entities = [
        Account::class,
        AutoCategoryRule::class,
        AutoCategoryRuleTagCrossRef::class,
        Category::class,
        CredentialProfile::class,
        InstalledExtension::class,
        Tag::class,
        Transfer::class,
        TransferAnnotation::class,
        TransferTagCrossRef::class,
    ],
    version = 14,
    exportSchema = false,
)
@TypeConverters(AssetKindConverters::class)
abstract class MoneylookDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun autoCategoryRuleDao(): AutoCategoryRuleDao
    abstract fun categoryDao(): CategoryDao
    abstract fun credentialProfileDao(): CredentialProfileDao
    abstract fun installedExtensionDao(): InstalledExtensionDao
    abstract fun transferDao(): TransferDao
    abstract fun transferAnnotationDao(): TransferAnnotationDao
    abstract fun tagDao(): TagDao
    abstract fun syncResultDao(): SyncResultDao

    companion object {
        /** Seeds the full public catalog only when Room creates a brand-new database. */
        fun defaultClassificationSeedCallback() = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                DefaultClassificationSeeder.seedFreshDatabase(db)
            }
        }
    }
}
