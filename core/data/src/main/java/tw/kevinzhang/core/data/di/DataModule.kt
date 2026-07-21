package tw.kevinzhang.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.CredentialProfileDao
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.db.MIGRATION_5_6
import tw.kevinzhang.core.data.db.MIGRATION_6_7
import tw.kevinzhang.core.data.db.MIGRATION_7_8
import tw.kevinzhang.core.data.db.MIGRATION_8_9
import tw.kevinzhang.core.data.db.MIGRATION_9_10
import tw.kevinzhang.core.data.db.MoneylookDatabase
import tw.kevinzhang.core.data.db.TransferSyncStore
import tw.kevinzhang.core.data.db.TransferDao
import tw.kevinzhang.core.data.db.TransferCursorStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MoneylookDatabase =
        Room.databaseBuilder(context, MoneylookDatabase::class.java, "moneylook.db")
            .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideAccountDao(db: MoneylookDatabase): AccountDao = db.accountDao()

    @Provides
    fun provideCredentialProfileDao(db: MoneylookDatabase): CredentialProfileDao =
        db.credentialProfileDao()

    @Provides
    fun provideInstalledExtensionDao(db: MoneylookDatabase): InstalledExtensionDao =
        db.installedExtensionDao()

    @Provides
    fun provideTransferDao(db: MoneylookDatabase): TransferDao = db.transferDao()

    @Provides
    fun provideTransferCursorStore(transferDao: TransferDao): TransferCursorStore = transferDao

    @Provides
    fun provideTransferSyncStore(db: MoneylookDatabase): TransferSyncStore = db.syncResultDao()
}
