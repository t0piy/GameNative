package app.gamenative.utils

import java.io.File

/**
 * Version bookkeeping, not an entitlement check. A provider/local manifest pins a build; the
 * current Steam branch is not an available update for that install. Updating a pin remains an
 * explicit import + update/verify operation, never a reason to block Play automatically.
 *
 * This class deliberately has no Android, Steam session, provider HTTP or credential dependency.
 */
internal object SteamInstallVersionPolicy {
    // DepotConfigStore.begin_depot() uses this value while an installation is in progress.
    private val unfinishedManifestId = Long.MAX_VALUE.toULong()
    private val installedBlock = Regex(""""installedManifestIDs"\s*:\s*\{([^{}]*)\}""")
    private val installedEntry = Regex("""\s*"(\d+)"\s*:\s*(?:"(\d+)"|(\d+))\s*""")
    private val manifestFileName = Regex("""^(\d+)_(\d+)\.manifest$""")

    private fun usableManifestId(value: ULong): Boolean = value != 0uL && value != unfinishedManifestId

    /** Preserve uint64 GIDs as decimal text; parsing through Double loses their low bits. */
    internal fun parseInstalledManifestIds(text: String): Map<Int, ULong> {
        val trimmed = text.trim()
        if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) return emptyMap()
        val block = installedBlock.find(trimmed)?.groupValues?.get(1) ?: return emptyMap()
        return buildMap {
            block.split(',').forEach { entry ->
                val match = installedEntry.matchEntire(entry) ?: return@forEach
                val depotId = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
                val gid = match.groupValues[2].ifEmpty { match.groupValues[3] }
                    .toULongOrNull() ?: return@forEach
                if (usableManifestId(gid)) put(depotId, gid)
            }
        }
    }

    /**
     * The downloader journal is authoritative, including an empty/unfinished journal. Merely
     * having a manifest in cache does NOT mean that its files have finished downloading.
     * For older installs without a journal, accept only one unambiguous cached version per depot.
     */
    fun readInstalledManifestIds(cacheDir: File): Map<Int, ULong> {
        val journal = File(cacheDir, "depot.config")
        if (journal.exists()) {
            return runCatching { parseInstalledManifestIds(journal.readText()) }.getOrDefault(emptyMap())
        }
        val candidates = cacheDir.listFiles().orEmpty()
            .filter { it.isFile }
            .mapNotNull { file ->
                val match = manifestFileName.matchEntire(file.name) ?: return@mapNotNull null
                val depotId = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                val gid = match.groupValues[2].toULongOrNull()?.takeIf(::usableManifestId)
                    ?: return@mapNotNull null
                depotId to gid
            }
            .groupBy({ it.first }, { it.second })
        return candidates.mapNotNull { (id, gids) -> gids.distinct().singleOrNull()?.let { id to it } }.toMap()
    }

    /** Ignore unrelated/uninstalled DLC pins and mere Explorer entries added by AppID. */
    fun isExternallyManaged(
        installed: Map<Int, ULong>,
        importedPins: Map<Int, ULong>,
    ): Boolean = installed.any { (depotId, gid) ->
        usableManifestId(gid) && importedPins[depotId]?.let(::usableManifestId) == true
    }

    fun isStoreUpdatePending(
        installed: Map<Int, ULong>,
        storeManifests: Map<Int, ULong>,
        importedPins: Map<Int, ULong>,
    ): Boolean {
        if (isExternallyManaged(installed, importedPins)) return false
        return installed.any { (depotId, onDisk) ->
            val advertised = storeManifests[depotId]
            usableManifestId(onDisk) && advertised != null && usableManifestId(advertised) && advertised != onDisk
        }
    }

    /** Never label an older/pinned install with the current branch's unrelated build ID. */
    fun clientBuildId(
        installed: Map<Int, ULong>,
        storeManifests: Map<Int, ULong>,
        storeBuildId: Long,
        previousInstalled: Map<Int, ULong> = emptyMap(),
        previousBuildId: Long = 0L,
    ): Long = when {
        installed.isEmpty() -> 0L
        installed == previousInstalled && previousBuildId > 0L -> previousBuildId
        installed.all { (id, gid) -> usableManifestId(gid) && storeManifests[id] == gid } ->
            storeBuildId.coerceAtLeast(0L)
        else -> 0L // unknown: a manifest GID is not a Steam build ID
    }

    data class ClientDepot(val manifestId: ULong, val sizeOnDisk: Long)

    /** The client ACF describes committed files, never just imported or current Store manifests. */
    fun installedDepotsAcf(depots: Map<Int, ClientDepot>): String = buildString {
        if (depots.isEmpty()) return@buildString
        appendLine("\t\"InstalledDepots\"")
        appendLine("\t{")
        depots.toSortedMap().forEach { (id, depot) ->
            if (id <= 0 || !usableManifestId(depot.manifestId)) return@forEach
            appendLine("\t\t\"$id\"")
            appendLine("\t\t{")
            appendLine("\t\t\t\"manifest\"\t\t\"${depot.manifestId}\"")
            appendLine("\t\t\t\"size\"\t\t\"${depot.sizeOnDisk.coerceAtLeast(0L)}\"")
            appendLine("\t\t}")
        }
        appendLine("\t}")
    }
}
