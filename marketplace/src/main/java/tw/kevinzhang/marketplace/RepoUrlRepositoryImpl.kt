package tw.kevinzhang.marketplace

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "marketplace_prefs")

class RepoUrlRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : RepoUrlRepository {

    private val repoUrlsKey = stringSetPreferencesKey("repo_urls")

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
