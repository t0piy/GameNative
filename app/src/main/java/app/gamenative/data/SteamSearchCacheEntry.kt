package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistent cache for Steam Store searches and dynamically-discovered Store filter metadata.
 *
 * Search cache is intentionally separate from steam_catalog: it can contain bundles/packages and
 * other Store rows that are not Steam app IDs and therefore cannot live in SteamApp.
 */
@Entity(tableName = "steam_search_cache")
data class SteamSearchCacheEntry(
    @PrimaryKey
    @ColumnInfo(name = "cache_key")
    val cacheKey: String,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "last_accessed_at")
    val lastAccessedAt: Long,
)
