package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gamenative.data.SteamManualEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface SteamManualEntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: SteamManualEntry)

    @Query("DELETE FROM steam_manual_entry WHERE app_id = :appId")
    suspend fun delete(appId: Int)

    @Query("SELECT * FROM steam_manual_entry ORDER BY added_at DESC")
    fun observeAll(): Flow<List<SteamManualEntry>>

    @Query("SELECT * FROM steam_manual_entry WHERE app_id = :appId LIMIT 1")
    suspend fun find(appId: Int): SteamManualEntry?
}
