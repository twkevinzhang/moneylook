package tw.kevinzhang.marketplace

import kotlinx.coroutines.flow.Flow

interface RepoUrlRepository {
    fun observeRepoUrls(): Flow<Set<String>>
    suspend fun addRepoUrl(url: String)
    suspend fun removeRepoUrl(url: String)
}
