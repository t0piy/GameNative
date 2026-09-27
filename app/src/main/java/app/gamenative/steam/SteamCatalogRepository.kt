package app.gamenative.steam

import app.gamenative.PrefManager
import app.gamenative.data.SteamCatalogEntry
import app.gamenative.db.dao.SteamCatalogDao
import app.gamenative.enums.AppType
import app.gamenative.utils.generateSteamApp
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
    private const val STORE_SEARCH_LIMIT = 100

    private val storeHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val syncMutex = Mutex()
    private val syncRequested = AtomicBoolean(false)
    private val _syncState = MutableStateFlow(SyncState())
    val syncState = _syncState.asStateFlow()

    /**
     * Global Steam Store search used as the discovery fallback. It does not need a Web API key;
     * the authenticated Steam session is still used when opening a result to obtain PICS metadata.
     */
    suspend fun searchStore(query: String): List<SteamCatalogEntry> {
        val term = query.trim()
        if (term.length < 2) return emptyList()

        val language = Locale.getDefault().language.ifBlank { "english" }
        val country = Locale.getDefault().country.ifBlank { "US" }
        val encoded = URLEncoder.encode(term, StandardCharsets.UTF_8.name())
        val url =
            "https://store.steampowered.com/api/storesearch/" +
                "?term=$encoded&l=$language&cc=$country&category1=998"

        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "GameNative-LuaTools")
                .build()
            storeHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@use emptyList()
                val items = JSONObject(body).optJSONArray("items") ?: return@use emptyList()
                buildList {
                    for (index in 0 until minOf(items.length(), STORE_SEARCH_LIMIT)) {
                        val item = items.optJSONObject(index) ?: continue
                        if (item.optString("type", "app") != "app") continue
                        val appId = item.optInt("id", 0)
                        val name = item.optString("name").trim()
                        if (appId > 0 && name.isNotBlank()) {
                            add(SteamCatalogEntry(appId = appId, name = name))
                        }
                    }
                }
            }
        }.onFailure {
            Timber.w(it, "Steam Store search failed for query=$term")
        }.getOrDefault(emptyList())
    }

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
                    val indexedSoFar = runCatching { dao.count() }
                        .getOrDefault(_syncState.value.indexedGames)
                    _syncState.update {
                        it.copy(
                            isSyncing = true,
                            processedChanges = processed,
                            indexedGames = indexedSoFar,
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
