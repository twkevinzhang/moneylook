package tw.kevinzhang.marketplace

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "marketplace_prefs")

private const val DEFAULT_REPO_URL = "https://github.com/twkevinzhang/moneylook-extensions"

class RepoUrlRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : RepoUrlRepository {

    private val repoUrlsKey = stringSetPreferencesKey("repo_urls")
    private val initializedKey = booleanPreferencesKey("repo_urls_initialized")

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val prefs = context.dataStore.data.first()
            if (prefs[initializedKey] != true) {
                context.dataStore.edit { it ->
                    it[repoUrlsKey] = setOf(DEFAULT_REPO_URL)
                    it[initializedKey] = true
                }
            }
        }
    }

    override fun observeRepoUrls(): Flow<Set<String>> =
        context.dataStore.data.map { prefs -> prefs[repoUrlsKey] ?: emptySet() }

    override suspend fun addRepoUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[repoUrlsKey] = (prefs[repoUrlsKey] ?: emptySet()) + url
        }
    }

    override suspend fun removeRepoUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[repoUrlsKey] = (prefs[repoUrlsKey] ?: emptySet()) - url
        }
    }
}
