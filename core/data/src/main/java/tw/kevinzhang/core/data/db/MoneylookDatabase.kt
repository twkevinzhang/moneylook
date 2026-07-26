package tw.kevinzhang.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleTagCrossRef
import tw.kevinzhang.core.data.model.AutoCategoryRuleCondition
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.CreditCardInstrument
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.IngestionRun
import tw.kevinzhang.core.data.model.AutoCategoryRuleSet
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.TransferAnnotation
import tw.kevinzhang.core.data.model.TransferAnnotationEvent
import tw.kevinzhang.core.data.model.TransferIngestionEvent
import tw.kevinzhang.core.data.model.TransferTagCrossRef
import tw.kevinzhang.core.data.model.SyncDiagnostic
import tw.kevinzhang.core.data.model.SourceDocument
import tw.kevinzhang.core.data.model.TransferFieldObservation
import tw.kevinzhang.core.data.model.ClassificationRuleEvaluation
import tw.kevinzhang.core.data.model.ClassificationConditionEvaluation

@Database(
    entities = [
        Account::class,
        AutoCategoryRule::class,
        AutoCategoryRuleTagCrossRef::class,
        AutoCategoryRuleCondition::class,
        Category::class,
        CredentialProfile::class,
        CreditCardInstrument::class,
        InstalledExtension::class,
        IngestionRun::class,
        AutoCategoryRuleSet::class,
        Tag::class,
        Transfer::class,
        TransferAnnotation::class,
        TransferAnnotationEvent::class,
        TransferIngestionEvent::class,
        TransferTagCrossRef::class,
        SyncDiagnostic::class,
        SourceDocument::class,
        TransferFieldObservation::class,
        ClassificationRuleEvaluation::class,
        ClassificationConditionEvaluation::class,
    ],
    version = 22,
    exportSchema = false,
)
@TypeConverters(AssetKindConverters::class)
abstract class MoneylookDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun autoCategoryRuleDao(): AutoCategoryRuleDao
    abstract fun autoCategoryRuleSetDao(): AutoCategoryRuleSetDao
    abstract fun categoryDao(): CategoryDao
    abstract fun credentialProfileDao(): CredentialProfileDao
    abstract fun creditCardInstrumentDao(): CreditCardInstrumentDao
    abstract fun installedExtensionDao(): InstalledExtensionDao
    abstract fun ingestionProvenanceDao(): IngestionProvenanceDao
    abstract fun transferDao(): TransferDao
    abstract fun transferAnnotationDao(): TransferAnnotationDao
    abstract fun tagDao(): TagDao
    abstract fun syncResultDao(): SyncResultDao
    abstract fun syncDiagnosticDao(): SyncDiagnosticDao

    companion object {
        /** Seeds the full public catalog only when Room creates a brand-new database. */
        fun defaultClassificationSeedCallback() = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                DefaultClassificationSeeder.seedFreshDatabase(db)
            }
        }
    }
}
