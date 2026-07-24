package tw.kevinzhang.moneylook.security

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class CardPanCryptoModule {
    @Binds
    abstract fun bindCardPanProtector(impl: AndroidKeystoreCardPanProtector): CardPanProtector

    @Binds
    abstract fun bindSourceFingerprintProtector(
        impl: AndroidKeystoreSourceFingerprintProtector,
    ): SourceFingerprintProtector
}
