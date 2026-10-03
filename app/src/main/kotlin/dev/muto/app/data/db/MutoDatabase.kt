package dev.muto.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RuleEntity::class,
        SubscriptionEntity::class,
        QueryLogEntity::class,
        TunnelEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MutoDatabase : RoomDatabase() {

    abstract fun rules(): RuleDao
    abstract fun subscriptions(): SubscriptionDao
    abstract fun queryLog(): QueryLogDao
    abstract fun tunnels(): TunnelDao

    companion object {
        fun create(context: Context): MutoDatabase =
            Room.databaseBuilder(context, MutoDatabase::class.java, "muto.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /**
         * Adds the tunnel table. Written out rather than destroying and recreating, because by
         * the time anyone upgrades they may have rules and subscriptions worth keeping.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tunnels` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `config` TEXT NOT NULL,
                        `lastConnectedAt` INTEGER,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tunnels_name` ON `tunnels` (`name`)")
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun toRuleAction(value: String): RuleAction = RuleAction.valueOf(value)

    @TypeConverter
    fun fromRuleAction(value: RuleAction): String = value.name
}
