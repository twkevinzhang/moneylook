package tw.kevinzhang.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Removes obsolete native-login policy columns without deleting installed extensions or credentials. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE `credential_profiles_backup` (
                `extensionId` TEXT NOT NULL,
                `username` TEXT NOT NULL,
                `password` TEXT NOT NULL,
                `scheduleEnabled` INTEGER NOT NULL,
                `scheduleCron` TEXT NOT NULL,
                `timezoneId` TEXT NOT NULL,
                `lastRunAt` INTEGER,
                `lastRunStatus` TEXT,
                PRIMARY KEY(`extensionId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `credential_profiles_backup` (
                `extensionId`, `username`, `password`, `scheduleEnabled`, `scheduleCron`,
                `timezoneId`, `lastRunAt`, `lastRunStatus`
            )
            SELECT `extensionId`, `username`, `password`, `scheduleEnabled`, `scheduleCron`,
                `timezoneId`, `lastRunAt`, `lastRunStatus`
            FROM `credential_profiles`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `credential_profiles`")

        db.execSQL(
            """
            CREATE TABLE `installed_extensions_new` (
                `id` TEXT NOT NULL,
                `manifestId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `version` INTEGER NOT NULL,
                `repoUrl` TEXT NOT NULL,
                `syncTriggerCachePath` TEXT NOT NULL,
                `iconUrl` TEXT,
                `suggestedScheduleCron` TEXT,
                `suggestedScheduleTimezone` TEXT NOT NULL,
                `suggestedScheduleEnabled` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `installed_extensions_new` (
                `id`, `manifestId`, `name`, `version`, `repoUrl`, `syncTriggerCachePath`,
                `iconUrl`, `suggestedScheduleCron`, `suggestedScheduleTimezone`,
                `suggestedScheduleEnabled`
            )
            SELECT `id`, `manifestId`, `name`, `version`, `repoUrl`, `syncTriggerCachePath`,
                `iconUrl`, `suggestedScheduleCron`, `suggestedScheduleTimezone`,
                `suggestedScheduleEnabled`
            FROM `installed_extensions`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `installed_extensions`")
        db.execSQL("ALTER TABLE `installed_extensions_new` RENAME TO `installed_extensions`")

        db.execSQL(
            """
            CREATE TABLE `credential_profiles` (
                `extensionId` TEXT NOT NULL,
                `username` TEXT NOT NULL,
                `password` TEXT NOT NULL,
                `scheduleEnabled` INTEGER NOT NULL,
                `scheduleCron` TEXT NOT NULL,
                `timezoneId` TEXT NOT NULL,
                `lastRunAt` INTEGER,
                `lastRunStatus` TEXT,
                PRIMARY KEY(`extensionId`),
                FOREIGN KEY(`extensionId`) REFERENCES `installed_extensions`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `credential_profiles` (
                `extensionId`, `username`, `password`, `scheduleEnabled`, `scheduleCron`,
                `timezoneId`, `lastRunAt`, `lastRunStatus`
            )
            SELECT `extensionId`, `username`, `password`, `scheduleEnabled`, `scheduleCron`,
                `timezoneId`, `lastRunAt`, `lastRunStatus`
            FROM `credential_profiles_backup`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `credential_profiles_backup`")
    }
}
