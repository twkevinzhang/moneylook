package tw.kevinzhang.core.data.db

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.TransferAnnotation

@RunWith(RobolectricTestRunner::class)
class UserMetadataDaoTest {
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
    fun `manual save replaces automatic tags and category deletion clears annotation category`() = runBlocking {
        val categories = database.categoryDao()
        val tags = database.tagDao()
        val annotations = database.transferAnnotationDao()
        categories.upsert(Category("food", "餐飲", "#FF0000"))
        tags.upsert(Tag("coffee", "咖啡", "#AA0000"))
        tags.upsert(Tag("receipt", "收據", "#00AA00"))

        annotations.upsert(
            TransferAnnotation(
                transferId = "transfer",
                extensionId = "extension",
                categoryId = "food",
            ),
        )
        annotations.replaceAutoTags("transfer", setOf("coffee"))
        annotations.saveManualAnnotation(
            TransferAnnotation(
                transferId = "transfer",
                extensionId = "extension",
                categoryId = "food",
                note = "報帳",
                categoryAssignment = AssignmentSource.MANUAL,
            ),
            setOf("receipt"),
        )

        assertEquals(
            listOf("receipt"),
            annotations.getTagCrossRefs("transfer").map { it.tagId },
        )
        assertEquals(AssignmentSource.MANUAL, annotations.getTagCrossRefs("transfer").single().source)
        categories.deleteById("food")
        assertNull(annotations.getByTransferIds(listOf("transfer")).single().categoryId)
    }

    @Test
    fun `auto tags never overwrite a matching manual tag and extension cleanup removes orphan metadata`() = runBlocking {
        val tags = database.tagDao()
        val annotations = database.transferAnnotationDao()
        tags.upsert(Tag("tag", "標籤", "#0000AA"))
        annotations.upsert(
            TransferAnnotation(
                transferId = "transfer",
                extensionId = "extension",
                categoryAssignment = AssignmentSource.MANUAL,
            ),
        )
        annotations.replaceManualTags("transfer", setOf("tag"))
        annotations.replaceAutoTags("transfer", setOf("tag"))

        assertEquals(AssignmentSource.MANUAL, annotations.getTagCrossRefs("transfer").single().source)
        annotations.clearForExtension("extension")
        assertEquals(emptyList<TransferAnnotation>(), annotations.getByTransferIds(listOf("transfer")))
        assertEquals(0, annotations.getTagCrossRefs("transfer").size)
    }
}
