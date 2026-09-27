package app.gamenative.utils

import android.content.Context
import java.io.File
import timber.log.Timber

/**
 * A version pin imported from a LuaTools-style `setManifestid(...)` directive or from a
 * locally supplied Steam depot manifest.
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
 * Stores imported Lua pins and raw Steam depot manifests per Steam app.
 *
 * All source files live in GameNative private storage. Raw manifests are copied into the real
 * `.DepotDownloader` cache only after Steam has granted the depot key for that download.
 */
object SteamManifestOverrideStore {
    private const val DIRECTORY = "steam-manifest-overrides"
    private const val MANIFESTS_DIRECTORY = "manifests"

    private val manifestNameRegex = Regex("""^(\d+)_(\d+)\.manifest$""", RegexOption.IGNORE_CASE)

    private fun root(context: Context): File = File(context.filesDir, DIRECTORY)

    fun fileFor(context: Context, appId: Int): File = File(root(context), "$appId.lua")

    private fun manifestRoot(context: Context, appId: Int): File =
        File(File(root(context), MANIFESTS_DIRECTORY), appId.toString())

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

        writeAtomically(fileFor(context, appId), luaText.toByteArray(Charsets.UTF_8))

        Timber.i(
            "Imported %d manifest override(s) for Steam app %d",
            parsed.size,
            appId,
        )
        return parsed.size
    }

    /**
     * Import a raw Steam depot manifest named `<depotId>_<manifestGid>.manifest`.
     *
     * The downloader performs the authoritative metadata depot/GID validation when the file is
     * staged. This import additionally checks the Steam manifest magic so arbitrary files cannot
     * be stored as depot manifests.
     */
    fun saveManifest(
        context: Context,
        appId: Int,
        fileName: String,
        bytes: ByteArray,
    ): SteamManifestOverride {
        require(appId > 0) { "Invalid Steam app id" }
        val override = parseManifestFileName(fileName)
            ?: throw IllegalArgumentException(
                "Manifest filename must be <depotId>_<manifestGid>.manifest",
            )
        require(isRawSteamManifest(bytes)) { "Selected file is not a raw Steam depot manifest" }

        val dir = manifestRoot(context, appId)
        check(dir.exists() || dir.mkdirs()) { "Could not create manifest override directory" }

        val normalizedName =
            "${override.depotId}_${java.lang.Long.toUnsignedString(override.manifestId)}.manifest"
        writeAtomically(File(dir, normalizedName), bytes)

        Timber.i(
            "Imported local manifest override depot=%d gid=%s for Steam app %d",
            override.depotId,
            java.lang.Long.toUnsignedString(override.manifestId),
            appId,
        )
        return override
    }

    fun load(context: Context, appId: Int): Map<Int, SteamManifestOverride> {
        if (appId <= 0) return emptyMap()

        val overrides = linkedMapOf<Int, SteamManifestOverride>()

        val luaFile = fileFor(context, appId)
        if (luaFile.isFile) {
            runCatching {
                LuaManifestOverrideParser.parse(luaFile.readText(Charsets.UTF_8))
            }.onFailure {
                Timber.w(it, "Could not read Steam manifest overrides for app %d", appId)
            }.getOrDefault(emptyMap()).forEach { (depotId, override) ->
                overrides[depotId] = override
            }
        }

        // A directly imported .manifest is the most explicit source, so it wins over a Lua pin
        // for the same depot.
        manifestRoot(context, appId).listFiles()
            ?.filter { it.isFile }
            ?.sortedBy { it.lastModified() }
            ?.forEach { file ->
                parseManifestFileName(file.name)?.let { override ->
                    overrides[override.depotId] = override
                }
            }

        return overrides
    }

    fun hasOverrides(context: Context, appId: Int): Boolean =
        load(context, appId).isNotEmpty()

    fun find(context: Context, appId: Int, depotId: Int): SteamManifestOverride? =
        load(context, appId)[depotId]

    /**
     * Copy a matching private raw manifest into the downloader's normal manifest cache.
     *
     * Call this only after Steam has granted the depot key. The Rust downloader then parses the
     * staged file, verifies that its metadata depot/GID match, decrypts filenames with the Steam
     * key and otherwise falls back to its normal CDN manifest fetch path if the cache is unusable.
     */
    fun stageLocalManifest(
        context: Context,
        appId: Int,
        depotId: Int,
        manifestId: Long,
        installDir: String,
    ): Boolean {
        val source = localManifestFile(context, appId, depotId, manifestId) ?: return false
        val destination = DepotManifestFiles.manifestFile(installDir, depotId, manifestId)
        val parent = destination.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false

        return runCatching {
            source.copyTo(destination, overwrite = true)
            true
        }.onFailure {
            Timber.w(
                it,
                "Could not stage local manifest depot=%d gid=%s for app %d",
                depotId,
                java.lang.Long.toUnsignedString(manifestId),
                appId,
            )
        }.getOrDefault(false)
    }

    fun clear(context: Context, appId: Int): Boolean {
        if (appId <= 0) return false

        var success = true
        val luaFile = fileFor(context, appId)
        if (luaFile.exists() && !luaFile.delete()) success = false

        val manifests = manifestRoot(context, appId)
        if (manifests.exists() && !manifests.deleteRecursively()) success = false

        return success
    }

    fun parseManifestFileName(fileName: String): SteamManifestOverride? {
        val match = manifestNameRegex.matchEntire(fileName.substringAfterLast('/')) ?: return null
        val depotId = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val manifestId = match.groupValues[2].toULongOrNull()
            ?.takeIf { it != 0uL }
            ?.toLong()
            ?: return null
        return SteamManifestOverride(depotId = depotId, manifestId = manifestId)
    }

    fun isRawSteamManifest(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0xD0.toByte() &&
            bytes[1] == 0x17.toByte() &&
            bytes[2] == 0xF6.toByte() &&
            bytes[3] == 0x71.toByte()

    private fun localManifestFile(
        context: Context,
        appId: Int,
        depotId: Int,
        manifestId: Long,
    ): File? {
        val name = "${depotId}_${java.lang.Long.toUnsignedString(manifestId)}.manifest"
        return File(manifestRoot(context, appId), name).takeIf { it.isFile }
    }

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        val parent = destination.parentFile
            ?: throw IllegalStateException("Missing destination directory")
        check(parent.exists() || parent.mkdirs()) { "Could not create manifest override directory" }

        val temporary = File(parent, destination.name + ".tmp")
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }
}
