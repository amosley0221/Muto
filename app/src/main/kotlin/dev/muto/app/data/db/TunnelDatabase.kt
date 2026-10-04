package dev.muto.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Tunnel configurations, kept in their own database file.
 *
 * Separate from [MutoDatabase] for one reason: a WireGuard config contains a private key, and
 * Android's backup rules work per file, not per table. Keeping tunnels here means the backup
 * configuration can take the user's rules and subscriptions - worth restoring onto a new phone -
 * while leaving key material out of cloud backup entirely.
 */
@Database(entities = [TunnelEntity::class], version = 1, exportSchema = true)
abstract class TunnelDatabase : RoomDatabase() {

    abstract fun tunnels(): TunnelDao

    companion object {
        /** Named so the backup rules can leave it out by simply not including it. */
        const val FILE_NAME = "muto-tunnels.db"

        fun create(context: Context): TunnelDatabase =
            Room.databaseBuilder(context, TunnelDatabase::class.java, FILE_NAME).build()
    }
}
