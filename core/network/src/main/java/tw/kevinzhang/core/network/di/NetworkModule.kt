package tw.kevinzhang.core.network.di

import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import tw.kevinzhang.core.network.BuildConfig
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .run {
            if (BuildConfig.DEBUG) {
                val logging = HttpLoggingInterceptor()
                // Never log request/response bodies: login payloads may contain
                // banking credentials and OCR requests contain captcha images.
                addInterceptor(logging.setLevel(HttpLoggingInterceptor.Level.BASIC))
            }
            readTimeout(10, TimeUnit.SECONDS)
            writeTimeout(10, TimeUnit.SECONDS)
            build()
        }

    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()
}
