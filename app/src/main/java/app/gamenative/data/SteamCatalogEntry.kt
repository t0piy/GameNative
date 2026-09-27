package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lightweight public Steam catalog row used by Explorer.
 *
 * This is deliberately separate from [SteamApp]: catalog entries are not proof of ownership
 * and must never participate in license or download decisions.
 */
@Entity(
    tableName = "steam_catalog",
    indices = [Index(value = ["name"])],
)
data class SteamCatalogEntry(
    @PrimaryKey
    @ColumnInfo(name = "app_id")
    val appId: Int,
    val name: String,
    @ColumnInfo(name = "last_change_number")
    val lastChangeNumber: Int = 0,
    @ColumnInfo(name = "icon_hash")
    val iconHash: String = "",
) {
    val capsuleUrl: String
        get() = "https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/library_600x900_2x.jpg"

    val headerUrl: String
        get() = "https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/header.jpg"

    val heroUrl: String
        get() = "https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/library_hero.jpg"
}
