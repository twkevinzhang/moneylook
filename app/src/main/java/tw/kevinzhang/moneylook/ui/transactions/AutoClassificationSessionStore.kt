package tw.kevinzhang.moneylook.ui.transactions

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Presentation hint only. Room remains the source of truth for work selection. */
@Singleton
class AutoClassificationSessionStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences("auto_classification_session", Context.MODE_PRIVATE)
    fun hasUnfinishedSession(): Boolean = preferences.getBoolean("unfinished", false)
    suspend fun setUnfinishedSession(value: Boolean) = withContext(Dispatchers.IO) {
        check(preferences.edit().putBoolean("unfinished", value).commit())
    }
}
