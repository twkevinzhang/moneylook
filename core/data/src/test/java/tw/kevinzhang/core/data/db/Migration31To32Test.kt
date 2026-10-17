package tw.kevinzhang.core.data.db

import androidx.room.Room
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Migration31To32Test {
    @Test fun cleanupPreservesUserMetadataAndRepurposedCategories() {
        val context = RuntimeEnvironment.getApplication()
        val name = "remove-fallback-test.db"
        context.deleteDatabase(name)
        try {
            val initial = Room.databaseBuilder(context, MoneylookDatabase::class.java, name).allowMainThreadQueries().build()
            val sql = initial.openHelper.writableDatabase
            for ((id, label, group) in listOf(
                Triple("local-fallback-expense-old", "其他／用途不明（支出）", "EXPENSE"),
                Triple("local-fallback-income-old", "其他／用途不明（收入） 2", "INCOME"),
                Triple("local-fallback-expense-renamed", "使用者改名", "EXPENSE"),
                Triple("local-fallback-expense-regrouped", "其他／用途不明（支出） 3", "EXCLUDED"),
                Triple("user-category", "使用者類別", "EXPENSE"),
            )) sql.execSQL("INSERT INTO categories(id,name,color,emoji,kind) VALUES(?,?, '#000000','',?)", arrayOf(id,label,group))
            for ((id, category, assignment) in listOf(
                Triple("auto", "local-fallback-expense-old", "AUTO"),
                Triple("manual", "local-fallback-income-old", "MANUAL"),
                Triple("other", "user-category", "AUTO"),
            )) sql.execSQL("INSERT INTO transfer_annotations(transferId,extensionId,categoryId,note,categoryAssignment,manualOverride) VALUES(?,'e',?,'keep note',?,?)", arrayOf(id,category,assignment,if (assignment == "MANUAL") 1 else 0))
            sql.execSQL("INSERT INTO tags(id,name,color) VALUES('tag','keep tag','#000000')")
            sql.execSQL("INSERT INTO transfer_tag_cross_refs(transferId,tagId,source) VALUES('auto','tag','MANUAL')")
            sql.execSQL("INSERT INTO classification_fallback_roles(role,categoryId) VALUES('EXPENSE','local-fallback-expense-old')")
            sql.version = 31
            initial.close()
            val migrated = Room.databaseBuilder(context, MoneylookDatabase::class.java, name).allowMainThreadQueries()
                .addMigrations(MIGRATION_31_32).build()
            try {
                val db = migrated.openHelper.writableDatabase
                db.query("SELECT transferId,categoryId,note,manualOverride FROM transfer_annotations ORDER BY transferId").use {
                    assertTrue(it.moveToFirst()); assertEquals("auto", it.getString(0)); assertTrue(it.isNull(1)); assertEquals("keep note", it.getString(2)); assertEquals(0,it.getInt(3))
                    assertTrue(it.moveToNext()); assertEquals("manual", it.getString(0)); assertTrue(it.isNull(1)); assertEquals(1,it.getInt(3))
                    assertTrue(it.moveToNext()); assertEquals("user-category", it.getString(1))
                }
                db.query("SELECT COUNT(*) FROM categories").use { assertTrue(it.moveToFirst()); assertEquals(3,it.getInt(0)) }
                db.query("SELECT COUNT(*) FROM transfer_tag_cross_refs WHERE tagId='tag'").use { assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)) }
                db.query("SELECT COUNT(*) FROM classification_fallback_roles").use { assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0)) }
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
