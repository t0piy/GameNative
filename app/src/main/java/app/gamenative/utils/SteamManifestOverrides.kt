package app.gamenative.utils

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * A version pin imported from a LuaTools-style `setManifestid(...)` directive or from a
 * locally supplied Steam depot manifest.
 *
 * This intentionally carries no depot key, app ticket, token, or other entitlement material.
 * GameNative still asks Steam for the depot key before the native downloader can fetch content.
 */
enum class ManifestOverrideSourceKind {
    LocalFile,
    RemoteUrl,
    DirectProvider,
    LuaToolsProvider,
    Hubcap,
    Unknown,
}

data class ManifestOverrideProvenance(
    val sourceKind: ManifestOverrideSourceKind,
    val sourceLabel: String,
    val importedAtEpochMillis: Long = System.currentTimeMillis(),
)

data class SteamManifestOverride(
    val depotId: Int,
    /**
     * Steam manifest GIDs are uint64 values. GameNative transports them in a signed [Long]
     * and serializes with Long.toUnsignedString(), so high-bit values are preserved as raw bits.
     */
    val manifestId: Long,
    val sizeOnDisk: Long? = null,
    val provenance: ManifestOverrideProvenance? = null,
    val namespaceAppId: Int? = null,
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
    private const val PROVENANCE_DIRECTORY = "provenance"
    private const val PAYLOAD_MAGIC = 0x71F617D0L
    private const val METADATA_MAGIC = 0x1F4812BEL
    private const val EOF_MAGIC = 0x32C415ABL
    private const val MAX_REMOTE_BYTES = 64L * 1024L * 1024L
    private const val MAX_PACKAGE_UNCOMPRESSED_BYTES = 128L * 1024L * 1024L
    private const val MAX_PACKAGE_ENTRIES = 512

    private val manifestNameRegex = Regex("""^(\d+)_(\d+)\.manifest$""", RegexOption.IGNORE_CASE)
    private val manifestMetadataCache =
        ConcurrentHashMap<String, Pair<Long, SteamManifestOverride>>()

    private fun root(context: Context): File = File(context.filesDir, DIRECTORY)

    fun fileFor(context: Context, appId: Int): File = File(root(context), "$appId.lua")

    private fun manifestRoot(context: Context, appId: Int): File =
        File(File(root(context), MANIFESTS_DIRECTORY), appId.toString())

    private fun provenanceFile(context: Context, appId: Int): File =
        File(File(root(context), PROVENANCE_DIRECTORY), "$appId.json")

    /**
     * Validate and persist a Lua manifest override file.
     *
     * @return the number of active setManifestid directives imported.
     * @throws IllegalArgumentException when the app id or file contains no usable manifest pins.
     */
    fun saveLua(
        context: Context,
        appId: Int,
        luaText: String,
        provenance: ManifestOverrideProvenance = ManifestOverrideProvenance(
            sourceKind = ManifestOverrideSourceKind.LocalFile,
            sourceLabel = "Local Lua file",
        ),
    ): Int {
        require(appId > 0) { "Invalid Steam app id" }

        val parsed = LuaManifestOverrideParser.parse(luaText)
        require(parsed.isNotEmpty()) { "No active setManifestid directives found" }

        val dir = root(context)
        check(dir.exists() || dir.mkdirs()) { "Could not create manifest override directory" }

        writeAtomically(fileFor(context, appId), luaText.toByteArray(Charsets.UTF_8))
        recordProvenance(
            context = context,
            appId = appId,
            overrides = parsed.values,
            provenance = provenance,
        )

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
    /**
     * Import a Lua pin file or raw depot manifest from an HTTPS URL.
     *
     * Only metadata is fetched here. Game content still comes from the normal Steam downloader,
     * and the depot key is still requested from Steam before a local manifest is staged.
     */
    suspend fun importFromUrl(
        context: Context,
        appId: Int,
        url: String,
    ): Int = withContext(Dispatchers.IO) {
        require(appId > 0) { "Invalid Steam app id" }
        val trimmed = url.trim()
        require(trimmed.startsWith("https://", ignoreCase = true)) {
            "Manifest source must use HTTPS"
        }

        val request = Request.Builder().url(trimmed).get().build()
        Net.http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            check(response.request.url.isHttps) { "Manifest source redirected away from HTTPS" }

            val body = response.body ?: error("Empty response body")
            val declaredLength = body.contentLength()
            require(declaredLength < 0L || declaredLength <= MAX_REMOTE_BYTES) {
                "Manifest source is too large"
            }

            val output = ByteArrayOutputStream(
                declaredLength.takeIf { it in 1..MAX_REMOTE_BYTES }?.toInt() ?: 16 * 1024,
            )
            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    require(total <= MAX_REMOTE_BYTES) { "Manifest source is too large" }
                    output.write(buffer, 0, read)
                }
            }

            val bytes = output.toByteArray()
            val fileName = response.request.url.pathSegments.lastOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "manifest.lua"

            val safeUrl = response.request.url.newBuilder()
                .query(null)
                .fragment(null)
                .build()
                .toString()
            importArtifact(
                context = context,
                appId = appId,
                fileName = fileName,
                bytes = bytes,
                provenance = ManifestOverrideProvenance(
                    sourceKind = ManifestOverrideSourceKind.RemoteUrl,
                    sourceLabel = safeUrl,
                ),
            )
        }
    }

    /**
     * Import the same artifact shapes LuaTools providers return: bare Lua, raw .manifest, or ZIP.
     *
     * ZIP entries are never extracted to arbitrary paths. Only Lua files for this AppID and raw
     * Steam manifests are consumed, with bounded entry count/uncompressed bytes.
     */
    fun importArtifact(
        context: Context,
        appId: Int,
        fileName: String,
        bytes: ByteArray,
        provenance: ManifestOverrideProvenance = ManifestOverrideProvenance(
            sourceKind = ManifestOverrideSourceKind.LocalFile,
            sourceLabel = fileName.ifBlank { "Local file" },
        ),
    ): Int {
        require(appId > 0) { "Invalid Steam app id" }
        return when {
            isZip(bytes) -> importZip(context, appId, bytes, provenance)
            isRawSteamManifest(bytes) -> {
                saveManifest(context, appId, fileName, bytes, provenance)
                1
            }
            else -> saveLua(context, appId, bytes.toString(Charsets.UTF_8), provenance)
        }
    }

    private fun importZip(
        context: Context,
        appId: Int,
        bytes: ByteArray,
        provenance: ManifestOverrideProvenance,
    ): Int {
        var imported = 0
        var entries = 0
        var uncompressed = 0L
        var selectedLua: Pair<String, ByteArray>? = null
        val pendingManifests = mutableListOf<Pair<String, ByteArray>>()

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_PACKAGE_ENTRIES) { "Manifest package has too many entries" }

                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }

                val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                val isLua = name.endsWith(".lua", ignoreCase = true)
                val isManifest = name.endsWith(".manifest", ignoreCase = true)
                if (!isLua && !isManifest) {
                    zip.closeEntry()
                    continue
                }

                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    uncompressed += read
                    require(uncompressed <= MAX_PACKAGE_UNCOMPRESSED_BYTES) {
                        "Manifest package is too large"
                    }
                    output.write(buffer, 0, read)
                }
                val entryBytes = output.toByteArray()

                if (isManifest) {
                    // Validate every manifest before committing any of them, so a bad ZIP entry
                    // cannot leave a half-imported provider package behind.
                    validateManifest(name, entryBytes)
                    pendingManifests += name to entryBytes
                } else if (luaEntryMatchesApp(name, appId)) {
                    // Prefer exact <appid>.lua over build-suffixed variants if both exist.
                    val exact = name.equals("$appId.lua", ignoreCase = true)
                    val currentExact = selectedLua?.first?.equals("$appId.lua", ignoreCase = true) == true
                    if (selectedLua == null || exact || !currentExact) {
                        selectedLua = name to entryBytes
                    }
                }
                zip.closeEntry()
            }
        }

        val selectedLuaText = selectedLua
            ?.second
            ?.toString(Charsets.UTF_8)
            ?.takeIf { LuaManifestOverrideParser.parse(it).isNotEmpty() }

        require(pendingManifests.isNotEmpty() || selectedLuaText != null) {
            "ZIP contains no usable manifest pins or Steam manifest files"
        }

        pendingManifests.forEach { (name, manifestBytes) ->
            saveManifest(context, appId, name, manifestBytes, provenance)
            imported++
        }
        selectedLuaText?.let {
            imported += saveLua(context, appId, it, provenance)
        }

        return imported
    }

    private fun luaEntryMatchesApp(fileName: String, appId: Int): Boolean {
        val stem = fileName.substringBeforeLast('.', fileName)
        val leading = Regex("""^\s*(\d+)""").find(stem)?.groupValues?.getOrNull(1)
        return leading?.toIntOrNull() == appId
    }

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() &&
            bytes[3] == 0x04.toByte()

    fun saveManifest(
        context: Context,
        appId: Int,
        fileName: String,
        bytes: ByteArray,
        provenance: ManifestOverrideProvenance = ManifestOverrideProvenance(
            sourceKind = ManifestOverrideSourceKind.LocalFile,
            sourceLabel = fileName.ifBlank { "Local manifest" },
        ),
    ): SteamManifestOverride {
        require(appId > 0) { "Invalid Steam app id" }
        val override = validateManifest(fileName, bytes)

        val dir = manifestRoot(context, appId)
        check(dir.exists() || dir.mkdirs()) { "Could not create manifest override directory" }

        val normalizedName =
            "${override.depotId}_${java.lang.Long.toUnsignedString(override.manifestId)}.manifest"
        val destination = File(dir, normalizedName)
        writeAtomically(destination, bytes)
        manifestMetadataCache[destination.absolutePath] = destination.lastModified() to override
        recordProvenance(
            context = context,
            appId = appId,
            overrides = listOf(override),
            provenance = provenance,
        )

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
        val provenance = readProvenance(context, appId)

        val luaFile = fileFor(context, appId)
        if (luaFile.isFile) {
            runCatching {
                LuaManifestOverrideParser.parse(luaFile.readText(Charsets.UTF_8))
            }.onFailure {
                Timber.w(it, "Could not read Steam manifest overrides for app %d", appId)
            }.getOrDefault(emptyMap()).forEach { (depotId, override) ->
                overrides[depotId] = override.copy(
                    provenance = provenance[provenanceKey(override)],
                    namespaceAppId = appId,
                )
            }
        }

        // A directly imported .manifest is the most explicit source, so it wins over a Lua pin
        // for the same depot.
        manifestRoot(context, appId).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".manifest", ignoreCase = true) }
            ?.sortedBy { it.lastModified() }
            ?.forEach { file ->
                overrideFromManifestFile(file)?.let { override ->
                    overrides[override.depotId] = override.copy(
                        provenance = provenance[provenanceKey(override)],
                        namespaceAppId = appId,
                    )
                }
            }

        return overrides
    }

    fun hasOverrides(context: Context, appId: Int): Boolean {
        if (appId <= 0) return false
        if (fileFor(context, appId).isFile) return true
        return manifestRoot(context, appId).listFiles()
            ?.any { it.isFile && it.name.endsWith(".manifest", ignoreCase = true) }
            == true
    }

    fun find(context: Context, appId: Int, depotId: Int): SteamManifestOverride? =
        load(context, appId)[depotId]

    fun owningAppId(
        parentAppId: Int,
        dlcAppId: Int,
        depotFromApp: Int,
        invalidAppId: Int,
    ): Int = when {
        dlcAppId != invalidAppId -> dlcAppId
        depotFromApp != invalidAppId -> depotFromApp
        else -> parentAppId
    }

    /**
     * DLC-aware lookup. The depot owner's namespace wins; the parent namespace is a compatibility
     * fallback for overrides imported before DLC namespaces were introduced.
     */
    fun findForDepot(
        context: Context,
        parentAppId: Int,
        depotId: Int,
        dlcAppId: Int,
        depotFromApp: Int,
        invalidAppId: Int,
    ): SteamManifestOverride? {
        val ownerAppId = owningAppId(
            parentAppId = parentAppId,
            dlcAppId = dlcAppId,
            depotFromApp = depotFromApp,
            invalidAppId = invalidAppId,
        )
        find(context, ownerAppId, depotId)?.let { return it }
        if (ownerAppId != parentAppId) {
            return find(context, parentAppId, depotId)
        }
        return null
    }

    /**
     * Copy a matching private raw manifest into the downloader's normal manifest cache.
     *
     * Call this only after Steam has granted the depot key. The Rust downloader then parses the
     * staged file, verifies that its metadata depot/GID match, decrypts filenames with the Steam
     * key and otherwise falls back to its normal CDN manifest fetch path if the cache is unusable.
     */
    fun stageLocalManifestForDepot(
        context: Context,
        parentAppId: Int,
        depotId: Int,
        dlcAppId: Int,
        depotFromApp: Int,
        invalidAppId: Int,
        manifestId: Long,
        installDir: String,
    ): Boolean {
        val ownerAppId = owningAppId(
            parentAppId = parentAppId,
            dlcAppId = dlcAppId,
            depotFromApp = depotFromApp,
            invalidAppId = invalidAppId,
        )
        if (
            stageLocalManifest(
                context = context,
                appId = ownerAppId,
                depotId = depotId,
                manifestId = manifestId,
                installDir = installDir,
            )
        ) {
            return true
        }

        return ownerAppId != parentAppId &&
            stageLocalManifest(
                context = context,
                appId = parentAppId,
                depotId = depotId,
                manifestId = manifestId,
                installDir = installDir,
            )
    }

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

        val provenance = provenanceFile(context, appId)
        if (provenance.exists() && !provenance.delete()) success = false

        val manifests = manifestRoot(context, appId)
        val manifestPrefix = manifests.absolutePath + File.separator
        manifestMetadataCache.keys.removeIf { it.startsWith(manifestPrefix) }
        if (manifests.exists() && !manifests.deleteRecursively()) success = false

        return success
    }

    private fun provenanceKey(override: SteamManifestOverride): String =
        "${override.depotId}:${java.lang.Long.toUnsignedString(override.manifestId)}"

    private fun readProvenance(
        context: Context,
        appId: Int,
    ): MutableMap<String, ManifestOverrideProvenance> {
        val file = provenanceFile(context, appId)
        if (!file.isFile) return linkedMapOf()

        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val entries = root.optJSONObject("entries") ?: JSONObject()
            buildMap {
                val keys = entries.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = entries.optJSONObject(key) ?: continue
                    val kind = runCatching {
                        ManifestOverrideSourceKind.valueOf(value.optString("kind"))
                    }.getOrDefault(ManifestOverrideSourceKind.Unknown)
                    val label = value.optString("label").ifBlank { "Unknown source" }
                    val importedAt = value.optLong("imported_at", 0L)
                    put(
                        key,
                        ManifestOverrideProvenance(
                            sourceKind = kind,
                            sourceLabel = label,
                            importedAtEpochMillis = importedAt,
                        ),
                    )
                }
            }.toMutableMap()
        }.onFailure {
            Timber.w(it, "Could not read manifest override provenance for app %d", appId)
        }.getOrDefault(linkedMapOf())
    }

    private fun recordProvenance(
        context: Context,
        appId: Int,
        overrides: Collection<SteamManifestOverride>,
        provenance: ManifestOverrideProvenance,
    ) {
        if (overrides.isEmpty()) return

        val current = readProvenance(context, appId)
        overrides.forEach { override ->
            val depotPrefix = "${override.depotId}:"
            current.keys.removeIf { it.startsWith(depotPrefix) }
            current[provenanceKey(override)] = provenance
        }

        val entries = JSONObject()
        current.forEach { (key, value) ->
            entries.put(
                key,
                JSONObject()
                    .put("kind", value.sourceKind.name)
                    .put("label", value.sourceLabel)
                    .put("imported_at", value.importedAtEpochMillis),
            )
        }

        val destination = provenanceFile(context, appId)
        writeAtomically(
            destination,
            JSONObject()
                .put("version", 1)
                .put("entries", entries)
                .toString()
                .toByteArray(Charsets.UTF_8),
        )
    }

    private fun validateManifest(
        fileName: String,
        bytes: ByteArray,
    ): SteamManifestOverride {
        val fileOverride = parseManifestFileName(fileName)
            ?: throw IllegalArgumentException(
                "Manifest filename must be <depotId>_<manifestGid>.manifest",
            )
        require(isRawSteamManifest(bytes)) { "Selected file is not a raw Steam depot manifest" }
        val metadata = parseRawSteamManifestMetadata(bytes)
            ?: throw IllegalArgumentException("Could not parse Steam manifest metadata")
        require(metadata.depotId == fileOverride.depotId) {
            "Manifest depot ID does not match its filename"
        }
        require(metadata.manifestId == fileOverride.manifestId) {
            "Manifest GID does not match its filename"
        }
        return fileOverride.copy(sizeOnDisk = metadata.sizeOnDisk)
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
        bytes.size >= 4 && readU32Le(bytes, 0) == PAYLOAD_MAGIC

    /**
     * Minimal reader for Steam's raw depot-manifest metadata section.
     *
     * It reads only depot id, manifest GID and uncompressed size. This lets the normal
     * DownloadInfo weighting/ETA use the alternate version's actual size without executing Lua.
     */
    fun parseRawSteamManifestMetadata(bytes: ByteArray): SteamManifestOverride? {
        var offset = 0
        while (offset + 8 <= bytes.size) {
            val magic = readU32Le(bytes, offset) ?: return null
            val length = readU32Le(bytes, offset + 4)?.toInt() ?: return null
            offset += 8

            if (magic == EOF_MAGIC) return null
            if (length < 0 || offset + length > bytes.size) return null

            if (magic == METADATA_MAGIC) {
                return parseMetadata(bytes, offset, offset + length)
            }
            offset += length
        }
        return null
    }

    private fun overrideFromManifestFile(file: File): SteamManifestOverride? {
        val named = parseManifestFileName(file.name) ?: return null
        val cached = manifestMetadataCache[file.absolutePath]
        if (cached != null && cached.first == file.lastModified()) return cached.second

        val parsed = runCatching {
            parseRawSteamManifestMetadata(file.readBytes())
        }.getOrNull() ?: return null
        if (parsed.depotId != named.depotId || parsed.manifestId != named.manifestId) return null

        manifestMetadataCache[file.absolutePath] = file.lastModified() to parsed
        return parsed
    }

    private fun parseMetadata(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): SteamManifestOverride? {
        var offset = start
        var depotId = 0
        var manifestId = 0L
        var sizeOnDisk = 0L

        while (offset < end) {
            val tag = readVarint(bytes, offset, end) ?: return null
            offset = tag.next
            val field = (tag.value shr 3).toInt()
            val wire = (tag.value and 7uL).toInt()

            if (wire == 0) {
                val value = readVarint(bytes, offset, end) ?: return null
                offset = value.next
                when (field) {
                    1 -> depotId = value.value.toLong().toInt()
                    2 -> manifestId = value.value.toLong()
                    5 -> sizeOnDisk = value.value.toLong()
                }
                continue
            }

            offset = skipField(bytes, offset, end, wire) ?: return null
        }

        if (depotId <= 0 || manifestId == 0L || sizeOnDisk < 0L) return null
        return SteamManifestOverride(
            depotId = depotId,
            manifestId = manifestId,
            sizeOnDisk = sizeOnDisk.takeIf { it > 0L },
        )
    }

    private data class VarintResult(val value: ULong, val next: Int)

    private fun readVarint(bytes: ByteArray, start: Int, end: Int): VarintResult? {
        var offset = start
        var shift = 0
        var value = 0uL
        while (offset < end && shift < 70) {
            val b = bytes[offset].toInt() and 0xff
            offset++
            value = value or (((b and 0x7f).toULong()) shl shift)
            if ((b and 0x80) == 0) return VarintResult(value, offset)
            shift += 7
        }
        return null
    }

    private fun skipField(bytes: ByteArray, start: Int, end: Int, wire: Int): Int? {
        return when (wire) {
            1 -> (start + 8).takeIf { it <= end }
            2 -> {
                val length = readVarint(bytes, start, end) ?: return null
                val next = length.next.toLong() + length.value.toLong()
                next.takeIf { it <= end.toLong() }?.toInt()
            }
            5 -> (start + 4).takeIf { it <= end }
            else -> null
        }
    }

    private fun readU32Le(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset + 4 > bytes.size) return null
        return (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)
    }

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
