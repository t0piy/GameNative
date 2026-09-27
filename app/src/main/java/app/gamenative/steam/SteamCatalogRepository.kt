package app.gamenative.steam

import app.gamenative.PrefManager
import app.gamenative.data.SteamCatalogEntry
import app.gamenative.db.dao.SteamCatalogDao
import app.gamenative.enums.AppType
import app.gamenative.utils.generateSteamApp
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Builds a searchable public Steam game catalog through the already-authenticated Steam session.
 *
 * Catalog rows are deliberately independent from the licensed Steam library. Selecting a result
 * hydrates SteamApp separately, still with an invalid package id unless the account owns it.
 */
object SteamCatalogRepository {
    data class SyncState(
        val isSyncing: Boolean = false,
        val totalChanges: Int = 0,
        val processedChanges: Int = 0,
        val indexedGames: Int = 0,
        val error: String? = null,
    )

    private const val BATCH_SIZE = 128

    private val syncMutex = Mutex()
    private val syncRequested = AtomicBoolean(false)
    private val _syncState = MutableStateFlow(SyncState())
    val syncState = _syncState.asStateFlow()

    suspend fun sync(
        steamApps: SteamApps,
        dao: SteamCatalogDao,
        forceFull: Boolean = false,
    ) {
        if (!syncRequested.compareAndSet(false, true)) return
        try {
            syncMutex.withLock {
                val existingCount = dao.count()
                val fromChangeNumber =
                    if (forceFull || existingCount == 0) 0 else PrefManager.steamCatalogChangeNumber

                _syncState.value = SyncState(isSyncing = true, indexedGames = existingCount)

                val changes = try {
                    steamApps.picsGetChangesSince(
                        lastChangeNumber = fromChangeNumber,
                        sendAppChangeList = true,
                        sendPackageChangelist = false,
                    ).await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Steam catalog PICS changelist failed")
                    _syncState.value = SyncState(
                        isSyncing = false,
                        indexedGames = existingCount,
                        error = e.message ?: e.javaClass.simpleName,
                    )
                    return@withLock
                }

                val appChanges = changes.appChanges.values
                    .filter { it.id > 0 }
                    .sortedBy { it.id }

                if (appChanges.isEmpty()) {
                    if (existingCount > 0) {
                        PrefManager.steamCatalogChangeNumber = changes.currentChangeNumber
                    }
                    _syncState.value = SyncState(
                        isSyncing = false,
                        indexedGames = existingCount,
                        error = if (existingCount == 0) {
                            "Steam did not return catalog entries."
                        } else {
                            null
                        },
                    )
                    return@withLock
                }

                _syncState.value = SyncState(
                    isSyncing = true,
                    totalChanges = appChanges.size,
                    indexedGames = existingCount,
                )

                var processed = 0
                var failed = false
                appChanges.chunked(BATCH_SIZE).forEach { batch ->
                    try {
                        val tokenIds = batch.filter { it.isNeedsToken }.map { it.id }
                        val tokens = if (tokenIds.isEmpty()) {
                            emptyMap()
                        } else {
                            steamApps.picsGetAccessTokens(
                                appIds = tokenIds,
                                packageIds = emptyList(),
                            ).await().appTokens
                        }

                        val requests = batch.map { change ->
                            PICSRequest(
                                id = change.id,
                                accessToken = tokens[change.id] ?: 0L,
                            )
                        }

                        val productInfo = steamApps.picsGetProductInfo(
                            apps = requests,
                            packages = emptyList(),
                        ).await()

                        val games = mutableListOf<SteamCatalogEntry>()
                        val nonGames = mutableListOf<Int>()

                        productInfo.results.forEach { result ->
                            result.apps.values.forEach { app ->
                                val parsed = app.keyValues.generateSteamApp()
                                if (parsed.type == AppType.game && parsed.name.isNotBlank()) {
                                    games += SteamCatalogEntry(
                                        appId = app.id,
                                        name = parsed.name,
                                        lastChangeNumber = app.changeNumber,
                                        iconHash = parsed.clientIconHash.ifBlank { parsed.iconHash },
                                    )
                                } else {
                                    nonGames += app.id
                                }
                            }
                        }

                        if (games.isNotEmpty()) dao.insertAll(games)
                        if (nonGames.isNotEmpty()) dao.deleteByAppIds(nonGames.distinct())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed = true
                        Timber.w(e, "Steam catalog PICS batch failed")
                    }

                    processed += batch.size
                    _syncState.update {
                        it.copy(
                            isSyncing = true,
                            processedChanges = processed,
                            indexedGames = runCatching { dao.count() }.getOrDefault(it.indexedGames),
                            error = if (failed) "Some Steam catalog batches will be retried." else null,
                        )
                    }
                }

                val indexed = dao.count()
                if (!failed) {
                    PrefManager.steamCatalogChangeNumber = changes.currentChangeNumber
                }

                _syncState.value = SyncState(
                    isSyncing = false,
                    totalChanges = appChanges.size,
                    processedChanges = processed,
                    indexedGames = indexed,
                    error = if (failed) "Some Steam catalog batches will be retried." else null,
                )
            }
        } finally {
            syncRequested.set(false)
        }
    }
}
