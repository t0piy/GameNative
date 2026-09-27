package app.gamenative.utils

import android.content.Context
import java.io.File
import timber.log.Timber

/**
 * A version pin imported from a LuaTools-style `setManifestid(...)` directive.
 *
 * This intentionally carries no depot key, app ticket, token, or other entitlement material.
 * GameNative still asks Steam for the depot key before the native downloader can fetch content.
 */
data class SteamManifestOverride(
    val depotId: Int,
    /**
     * Steam manifest GIDs are uint64 values. GameNative transports them in a signed [Long]
     * and serializes with Long.toUnsignedString(), so high-bit values are preserved as raw bits.
     */
    val manifestId: Long,
    val sizeOnDisk: Long? = null,
)

/**
 * Minimal, non-executing parser for the subset of LuaTools files that is useful to GameNative.
 *
 * We deliberately do NOT embed a Lua VM and do NOT interpret addappid/addtoken/ticket/key
 * directives. Only active `setManifestid(depot, "gid", optionalSize)` lines are accepted.
 */
object LuaManifestOverrideParser {
    private val setManifestRegex = Regex(
        """^setManifestid\s*\(\s*(\d+)\s*,\s*["']?(\d+)["']?\s*(?:,\s*(\d+))?""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(luaText: String): Map<Int, SteamManifestOverride> {
        val overrides = linkedMapOf<Int, SteamManifestOverride>()

        luaText.lineSequence().forEach { rawLine ->
            // Lua line comments. A line that begins with "--" therefore becomes empty and is ignored.
            val line = rawLine.substringBefore("--").trim()
            if (line.isEmpty()) return@forEach

            val match = setManifestRegex.find(line) ?: return@forEach
            val depotId = match.groupValues[1].toIntOrNull()
                ?.takeIf { it > 0 }
                ?: return@forEach
            val manifestId = match.groupValues[2].toULongOrNull()
                ?.takeIf { it != 0uL }
                ?.toLong()
                ?: return@forEach
            val size = match.groupValues.getOrNull(3)
                ?.takeIf { it.isNotBlank() }
                ?.toLongOrNull()
                ?.takeIf { it > 0L }

            // Last active pin wins, matching the effective behavior of repeated setManifestid calls.
            overrides[depotId] = SteamManifestOverride(
                depotId = depotId,
                manifestId = manifestId,
                sizeOnDisk = size,
            )
        }

        return overrides
    }
}

/**
 * Stores one imported LuaTools-style manifest file per Steam app.
 *
 * Files live in GameNative's private app storage. Keeping the original Lua text makes imports
 * reversible and lets future UI/provider work re-parse them without a database migration.
 */
object SteamManifestOverrideStore {
    private const val DIRECTORY = "steam-manifest-overrides"

    private fun root(context: Context): File = File(context.filesDir, DIRECTORY)

    fun fileFor(context: Context, appId: Int): File = File(root(context), "$appId.lua")

    /**
     * Validate and persist a Lua manifest override file.
     *
     * @return the number of active setManifestid directives imported.
     * @throws IllegalArgumentException when the app id or file contains no usable manifest pins.
     */
    fun saveLua(context: Context, appId: Int, luaText: String): Int {
        require(appId > 0) { "Invalid Steam app id" }

        val parsed = LuaManifestOverrideParser.parse(luaText)
        require(parsed.isNotEmpty()) { "No active setManifestid directives found" }

        val dir = root(context)
        check(dir.exists() || dir.mkdirs()) { "Could not create manifest override directory" }

        val destination = fileFor(context, appId)
        val temporary = File(dir, "$appId.lua.tmp")
        temporary.writeText(luaText, Charsets.UTF_8)

        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }

        Timber.i(
            "Imported %d manifest override(s) for Steam app %d",
            parsed.size,
            appId,
        )
        return parsed.size
    }

    fun load(context: Context, appId: Int): Map<Int, SteamManifestOverride> {
        if (appId <= 0) return emptyMap()
        val file = fileFor(context, appId)
        if (!file.isFile) return emptyMap()

        return runCatching {
            LuaManifestOverrideParser.parse(file.readText(Charsets.UTF_8))
        }.onFailure {
            Timber.w(it, "Could not read Steam manifest overrides for app %d", appId)
        }.getOrDefault(emptyMap())
    }

    fun find(context: Context, appId: Int, depotId: Int): SteamManifestOverride? =
        load(context, appId)[depotId]

    fun clear(context: Context, appId: Int): Boolean {
        if (appId <= 0) return false
        val file = fileFor(context, appId)
        return !file.exists() || file.delete()
    }
}
