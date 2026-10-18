package tw.kevinzhang.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Every rule write path (including import/conditions/tags) invalidates in-flight results atomically. */
fun installClassificationRuleTriggers(db: SupportSQLiteDatabase) {
    db.execSQL("INSERT OR IGNORE INTO classification_jobs (id,generation,preparedGeneration,status,fullReclassification,total,processed,message,attemptId) VALUES(1,0,-1,'IDLE',0,0,0,NULL,NULL)")
    listOf("auto_category_rules", "auto_category_rule_conditions", "auto_category_rule_tag_cross_refs").forEach { table ->
        listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
            db.execSQL("""CREATE TRIGGER IF NOT EXISTS classification_invalidate_${table}_${operation.lowercase()}
                AFTER $operation ON $table BEGIN
                UPDATE classification_jobs SET generation=generation+1,preparedGeneration=-1,fullReclassification=1,
                processed=0,total=0,message='自訂規則已變更，將重新分類全部非人工交易',
                status=CASE WHEN status IN ('QUEUED','RUNNING','RESTARTING') THEN 'RESTARTING'
                    WHEN status='STOPPING' THEN 'STOPPING' ELSE 'PAUSED' END WHERE id=1;
                END""".trimIndent())
        }
    }
}

val MIGRATION_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS classification_jobs (id INTEGER NOT NULL PRIMARY KEY,generation INTEGER NOT NULL,preparedGeneration INTEGER NOT NULL,status TEXT NOT NULL,fullReclassification INTEGER NOT NULL,total INTEGER NOT NULL,processed INTEGER NOT NULL,message TEXT,attemptId TEXT,startedAttemptId TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS classification_job_items (transferId TEXT NOT NULL PRIMARY KEY,generation INTEGER NOT NULL,completed INTEGER NOT NULL)")
        installClassificationRuleTriggers(db)
    }
}
