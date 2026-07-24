package tw.kevinzhang.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.AutoCategoryRuleDao
import tw.kevinzhang.core.data.db.AutoCategoryRuleSetDao
import tw.kevinzhang.core.data.db.CategoryDao
import tw.kevinzhang.core.data.db.CredentialProfileDao
import tw.kevinzhang.core.data.db.CreditCardInstrumentDao
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.db.MIGRATION_5_6
import tw.kevinzhang.core.data.db.MIGRATION_6_7
import tw.kevinzhang.core.data.db.MIGRATION_7_8
import tw.kevinzhang.core.data.db.MIGRATION_8_9
import tw.kevinzhang.core.data.db.MIGRATION_9_10
import tw.kevinzhang.core.data.db.MIGRATION_10_11
import tw.kevinzhang.core.data.db.MIGRATION_11_12
import tw.kevinzhang.core.data.db.MIGRATION_12_13
import tw.kevinzhang.core.data.db.MIGRATION_13_14
import tw.kevinzhang.core.data.db.MIGRATION_14_15
import tw.kevinzhang.core.data.db.MIGRATION_15_16
import tw.kevinzhang.core.data.db.MIGRATION_16_17
import tw.kevinzhang.core.data.db.MIGRATION_17_18
import tw.kevinzhang.core.data.db.MoneylookDatabase
import tw.kevinzhang.core.data.db.TransferSyncStore
import tw.kevinzhang.core.data.db.TransferDao
import tw.kevinzhang.core.data.db.TransferAnnotationDao
import tw.kevinzhang.core.data.db.TransferCursorStore
import tw.kevinzhang.core.data.db.TagDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MoneylookDatabase =
        Room.databaseBuilder(context, MoneylookDatabase::class.java, "moneylook.db")
            .addMigrations(
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
            )
            .addCallback(MoneylookDatabase.defaultClassificationSeedCallback())
            .build()

    @Provides
    fun provideAccountDao(db: MoneylookDatabase): AccountDao = db.accountDao()

    @Provides
    fun provideAutoCategoryRuleDao(db: MoneylookDatabase): AutoCategoryRuleDao = db.autoCategoryRuleDao()

    @Provides
    fun provideAutoCategoryRuleSetDao(db: MoneylookDatabase): AutoCategoryRuleSetDao = db.autoCategoryRuleSetDao()

    @Provides
    fun provideCategoryDao(db: MoneylookDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideCredentialProfileDao(db: MoneylookDatabase): CredentialProfileDao =
        db.credentialProfileDao()

    @Provides
    fun provideCreditCardInstrumentDao(db: MoneylookDatabase): CreditCardInstrumentDao =
        db.creditCardInstrumentDao()

    @Provides
    fun provideInstalledExtensionDao(db: MoneylookDatabase): InstalledExtensionDao =
        db.installedExtensionDao()

    @Provides
    fun provideTransferDao(db: MoneylookDatabase): TransferDao = db.transferDao()

    @Provides
    fun provideTransferAnnotationDao(db: MoneylookDatabase): TransferAnnotationDao =
        db.transferAnnotationDao()

    @Provides
    fun provideTagDao(db: MoneylookDatabase): TagDao = db.tagDao()

    @Provides
    fun provideTransferCursorStore(transferDao: TransferDao): TransferCursorStore = transferDao

    @Provides
    fun provideTransferSyncStore(db: MoneylookDatabase): TransferSyncStore = db.syncResultDao()
}
