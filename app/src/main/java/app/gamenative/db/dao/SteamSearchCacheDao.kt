package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gamenative.data.SteamSearchCacheEntry

@Dao
interface SteamSearchCacheDao {
    @Query("SELECT * FROM steam_search_cache WHERE cache_key = :key LIMIT 1")
    suspend fun get(key: String): SteamSearchCacheEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: SteamSearchCacheEntry)

    @Query("UPDATE steam_search_cache SET last_accessed_at = :timestamp WHERE cache_key = :key")
    suspend fun touch(key: String, timestamp: Long)

    @Query("DELETE FROM steam_search_cache WHERE updated_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query(
        """
        DELETE FROM steam_search_cache
        WHERE cache_key NOT IN (
            SELECT cache_key FROM steam_search_cache
            ORDER BY last_accessed_at DESC
            LIMIT :maxEntries
        )
        """,
    )
    suspend fun trimTo(maxEntries: Int)

    @Query("DELETE FROM steam_search_cache")
    suspend fun clear()
}
