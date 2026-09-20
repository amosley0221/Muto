package dev.muto.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(
    entities = [RuleEntity::class, SubscriptionEntity::class, QueryLogEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MutoDatabase : RoomDatabase() {

    abstract fun rules(): RuleDao
    abstract fun subscriptions(): SubscriptionDao
    abstract fun queryLog(): QueryLogDao

    companion object {
        fun create(context: Context): MutoDatabase =
            Room.databaseBuilder(context, MutoDatabase::class.java, "muto.db")
                .build()
    }
}

class Converters {
    @TypeConverter
    fun toRuleAction(value: String): RuleAction = RuleAction.valueOf(value)

    @TypeConverter
    fun fromRuleAction(value: RuleAction): String = value.name
}
