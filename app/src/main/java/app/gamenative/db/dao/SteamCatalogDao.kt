package app.gamenative.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.gamenative.data.SteamCatalogEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface SteamCatalogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<SteamCatalogEntry>)

    @Query("DELETE FROM steam_catalog WHERE app_id IN (:appIds)")
    suspend fun deleteByAppIds(appIds: List<Int>)

    @Query("DELETE FROM steam_catalog")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM steam_catalog")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM steam_catalog")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM steam_catalog WHERE app_id = :appId LIMIT 1")
    suspend fun find(appId: Int): SteamCatalogEntry?

    @Query(
        """
        SELECT * FROM steam_catalog
        WHERE :query = '' OR LOWER(name) LIKE '%' || LOWER(:query) || '%'
        ORDER BY
            CASE
                WHEN :query != '' AND LOWER(name) LIKE LOWER(:query) || '%' THEN 0
                ELSE 1
            END,
            LOWER(name),
            app_id
        LIMIT :limit
        """,
    )
    fun search(query: String, limit: Int = 200): Flow<List<SteamCatalogEntry>>
}
