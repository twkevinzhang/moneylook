package tw.kevinzhang.moneylook.di

import android.content.Context
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import tw.kevinzhang.extension_runtime.captcha.CaptchaSolver
import tw.kevinzhang.extension_runtime.captcha.FastApiCaptchaSolver
import tw.kevinzhang.extension_runtime.login.NativeLoginRunner
import tw.kevinzhang.extension_runtime.login.WebViewNativeLoginRunner
import tw.kevinzhang.moneylook.BuildConfig
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AutomationModule {

    @Provides
    @Singleton
    fun provideCaptchaSolver(
        okHttpClient: OkHttpClient,
        gson: Gson,
    ): CaptchaSolver {
        val baseUrl = BuildConfig.OCR_BASE_URL.trim().trimEnd('/')
        require(baseUrl.isNotEmpty()) {
            "MONEYLOOK_OCR_BASE_URL must be configured outside version control"
        }
        return FastApiCaptchaSolver(okHttpClient, "$baseUrl/ocr", gson)
    }

    @Provides
    @Singleton
    fun provideNativeLoginRunner(
        @ApplicationContext context: Context,
        captchaSolver: CaptchaSolver,
        gson: Gson,
    ): NativeLoginRunner = WebViewNativeLoginRunner(context, captchaSolver, gson)
}
