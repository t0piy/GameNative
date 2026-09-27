package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Steam Explorer entry explicitly added by AppID.
 *
 * This is discovery metadata only. It never represents ownership, a package license or download
 * entitlement.
 */
@Entity(tableName = "steam_manual_entry")
data class SteamManualEntry(
    @PrimaryKey
    @ColumnInfo(name = "app_id")
    val appId: Int,
    val name: String,
    @ColumnInfo(name = "image_url")
    val imageUrl: String = "",
    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis(),
)
