package tw.kevinzhang.extension_runtime.di

import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import tw.kevinzhang.extension_runtime.ExtensionRunner
import tw.kevinzhang.extension_runtime.ExtensionRunnerImpl
import tw.kevinzhang.extension_runtime.session.SessionStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RuntimeModule {

    @Provides
    @Singleton
    fun provideExtensionRunner(
        okHttpClient: OkHttpClient,
        sessionStore: SessionStore,
        gson: Gson,
    ): ExtensionRunner = ExtensionRunnerImpl(okHttpClient, sessionStore, gson)
}
