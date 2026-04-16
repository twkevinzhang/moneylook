package tw.kevinzhang.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.db.MoneylookDatabase
import tw.kevinzhang.core.data.db.TransferDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MoneylookDatabase =
        Room.databaseBuilder(context, MoneylookDatabase::class.java, "moneylook.db")
            .fallbackToDestructiveMigration(dropAllTables = true) // pre-production: replace with real Migration objects before first production release
            .build()

    @Provides
    fun provideAccountDao(db: MoneylookDatabase): AccountDao = db.accountDao()

    @Provides
    fun provideInstalledExtensionDao(db: MoneylookDatabase): InstalledExtensionDao =
        db.installedExtensionDao()

    @Provides
    fun provideTransferDao(db: MoneylookDatabase): TransferDao = db.transferDao()
}
