package tw.kevinzhang.marketplace.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tw.kevinzhang.marketplace.MarketplaceRepository
import tw.kevinzhang.marketplace.MarketplaceRepositoryImpl
import tw.kevinzhang.marketplace.RepoUrlRepository
import tw.kevinzhang.marketplace.RepoUrlRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MarketplaceModule {

    @Binds @Singleton
    abstract fun bindMarketplaceRepository(impl: MarketplaceRepositoryImpl): MarketplaceRepository

    @Binds @Singleton
    abstract fun bindRepoUrlRepository(impl: RepoUrlRepositoryImpl): RepoUrlRepository
}
