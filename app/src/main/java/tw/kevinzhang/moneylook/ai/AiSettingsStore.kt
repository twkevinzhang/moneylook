package tw.kevinzhang.moneylook.ai

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AiSettingsLoadResult {
    data class Ready(val settings: AiSettings) : AiSettingsLoadResult
    data object Missing : AiSettingsLoadResult
    data object CredentialsUnavailable : AiSettingsLoadResult
}

@Singleton
class AiSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
    private val protector: AiSecretProtector,
) {
    private val preferences = context.getSharedPreferences("ai_classification_settings", Context.MODE_PRIVATE)
    suspend fun load(): AiSettingsLoadResult = withContext(Dispatchers.IO) {
        val encrypted = preferences.getString("encrypted", null) ?: return@withContext AiSettingsLoadResult.Missing
        try {
            val settings = Gson().fromJson(protector.decrypt(encrypted), AiSettings::class.java)
            if (validateAiSettings(settings).isEmpty()) AiSettingsLoadResult.Ready(settings) else AiSettingsLoadResult.CredentialsUnavailable
        } catch (_: Exception) { AiSettingsLoadResult.CredentialsUnavailable }
    }
    suspend fun save(settings: AiSettings) = withContext(Dispatchers.IO) {
        require(validateAiSettings(settings).isEmpty())
        check(preferences.edit().putString("encrypted", protector.encrypt(Gson().toJson(settings))).commit())
    }
}
