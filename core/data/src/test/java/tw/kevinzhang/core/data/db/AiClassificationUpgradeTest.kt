package tw.kevinzhang.core.data.db

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AiClassificationUpgradeTest {
    @Test fun freshInstallHasCategoriesWithoutBundledRules() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MoneylookDatabase::class.java)
            .addCallback(MoneylookDatabase.defaultClassificationSeedCallback()).allowMainThreadQueries().build()
        try {
            assertTrue(db.categoryDao().observeAll().first().isNotEmpty())
            assertTrue(db.autoCategoryRuleDao().observeAll().first().isEmpty())
        } finally { db.close() }
    }
}
