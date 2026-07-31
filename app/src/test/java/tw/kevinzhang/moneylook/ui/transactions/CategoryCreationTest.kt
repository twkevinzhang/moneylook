package tw.kevinzhang.moneylook.ui.transactions

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.db.MoneylookDatabase
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CategoryReportingGroup

@RunWith(RobolectricTestRunner::class)
class CategoryCreationTest {
    private lateinit var database: MoneylookDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            MoneylookDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `new category is trimmed persisted and returned for selection`() = runBlocking {
        val result = persistNewCategory(
            categoryDao = database.categoryDao(),
            name = "  通勤  ",
            color = 0xFF123456,
            reportingGroup = CategoryReportingGroup.EXPENSE,
            idFactory = { "new-category" },
        )

        assertTrue(result is CategoryCreationResult.Created)
        val created = (result as CategoryCreationResult.Created).category
        assertEquals("new-category", created.id)
        assertEquals("通勤", created.name)
        assertEquals(CategoryReportingGroup.EXPENSE, created.reportingGroup)
        assertEquals("通勤", database.categoryDao().getById("new-category")?.name)
        assertEquals("#123456", database.categoryDao().getById("new-category")?.color)
    }

    @Test
    fun `duplicate category name is rejected case insensitively`() = runBlocking {
        database.categoryDao().upsert(
            Category(
                id = "existing",
                name = "Coffee",
                color = "#607D8B",
                reportingGroup = CategoryReportingGroup.EXPENSE,
            ),
        )

        val result = persistNewCategory(
            categoryDao = database.categoryDao(),
            name = " coffee ",
            color = 0xFF123456,
            reportingGroup = CategoryReportingGroup.EXPENSE,
            idFactory = { "duplicate" },
        )

        assertEquals(CategoryCreationResult.DuplicateName, result)
        assertEquals(null, database.categoryDao().getById("duplicate"))
    }
}
