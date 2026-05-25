package com.example.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "terminal_logs")
data class TerminalLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "type") val type: String, // "COMMAND", "OUTPUT", "ERROR", "SYSTEM"
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "provider") val provider: String
)

@Dao
interface TerminalLogDao {
    @Query("SELECT * FROM (SELECT * FROM terminal_logs ORDER BY id DESC LIMIT 200) ORDER BY id ASC")
    fun getAllLogs(): Flow<List<TerminalLog>>

    @Insert
    suspend fun insertLog(log: TerminalLog)

    @Query("DELETE FROM terminal_logs")
    suspend fun clearLogs()
}

@Database(entities = [TerminalLog::class], version = 1, exportSchema = false)
abstract class TerminalDatabase : RoomDatabase() {
    abstract fun terminalLogDao(): TerminalLogDao

    companion object {
        @Volatile
        private var INSTANCE: TerminalDatabase? = null

        fun getDatabase(context: Context): TerminalDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TerminalDatabase::class.java,
                    "terminal_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
