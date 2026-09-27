package app.gamenative.steam

import app.gamenative.PrefManager
import app.gamenative.data.SteamCatalogEntry
import app.gamenative.data.SteamSearchCacheEntry
import app.gamenative.db.dao.SteamCatalogDao
import app.gamenative.db.dao.SteamSearchCacheDao
import app.gamenative.utils.generateSteamApp
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.steam.handlers.steamapps.SteamApps
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Steam Explorer data source.
 *
 * Store search is search-first and supports the live Store filter surface. Room is an acceleration
 * and offline fallback layer, never the source of Steam ownership/entitlement.
 *
 * PICS remains useful for authenticated app metadata hydration and light background enrichment.
 */
object SteamCatalogRepository {
    data class SyncState(
        val isSyncing: Boolean = false,
        val totalChanges: Int = 0,
        val processedChanges: Int = 0,
        val indexedGames: Int = 0,
        val error: String? = null,
    )

    data class SearchOutcome(
        val results: List<SteamStoreSearchResult> = emptyList(),
        val totalCount: Int = 0,
        val fromCache: Boolean = false,
        val staleCache: Boolean = false,
        val error: String? = null,
    )

    data class FilterCatalogOutcome(
        val catalog: SteamStoreFilterCatalog = SteamStoreFilterCatalog(),
        val fromCache: Boolean = false,
        val staleCache: Boolean = false,
        val error: String? = null,
    )

    private const val BATCH_SIZE = 128
    private const val SEARCH_CACHE_FRESH_MS = 10 * 60 * 1000L
    private const val SEARCH_CACHE_STALE_MS = 7 * 24 * 60 * 60 * 1000L
    private const val FILTER_CACHE_FRESH_MS = 24 * 60 * 60 * 1000L
    private const val FILTER_CACHE_STALE_MS = 14 * 24 * 60 * 60 * 1000L
    private const val CACHE_RETENTION_MS = 14 * 24 * 60 * 60 * 1000L
    private const val CACHE_MAX_ENTRIES = 120
    private const val FILTER_CACHE_KEY_PREFIX = "steam-store:filters:v2"

    private val storeHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val syncMutex = Mutex()
    private val syncRequested = AtomicBoolean(false)
    private val _syncState = MutableStateFlow(SyncState())
    val syncState = _syncState.asStateFlow()

    /**
     * Search every product type exposed by Steam Store.
     *
     * No category1 restriction is injected: if the user does not select product types, Steam
     * returns its complete Store surface. Filters are passed through from the live data-param /
     * data-value metadata discovered from Steam's search page.
     */
    suspend fun searchStore(
        query: String,
        filters: SteamStoreSearchFilters,
        cacheDao: SteamSearchCacheDao,
        forceRefresh: Boolean = false,
    ): SearchOutcome {
        val now = System.currentTimeMillis()
        val cacheKey = "steam-store:search:v3:${steamCountry()}:${steamLanguage()}:${filters.canonicalKey(query)}"
        val cached = readSearchCache(cacheDao, cacheKey, now)

        if (!forceRefresh && cached != null && now - cached.first.updatedAt <= SEARCH_CACHE_FRESH_MS) {
            cacheDao.touch(cacheKey, now)
            return cached.second.copy(fromCache = true, staleCache = false)
        }

        return try {
            val url = buildSearchUrl(query, filters)
            val body = execute(url)
            val parsed = SteamStoreSearchParser.parseSearchResponse(body)
            val outcome = SearchOutcome(
                results = parsed.results,
                totalCount = parsed.totalCount,
                fromCache = false,
            )
            cacheDao.put(
                SteamSearchCacheEntry(
                    cacheKey = cacheKey,
                    payloadJson = encodeSearch(outcome),
                    updatedAt = now,
                    lastAccessedAt = now,
                ),
            )
            maintainCache(cacheDao, now)
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Steam Store search failed")
            if (cached != null && now - cached.first.updatedAt <= SEARCH_CACHE_STALE_MS) {
                cacheDao.touch(cacheKey, now)
                cached.second.copy(
                    fromCache = true,
                    staleCache = true,
                    error = e.message ?: e.javaClass.simpleName,
                )
            } else {
                SearchOutcome(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Discover the complete filter surface from Steam itself. This includes tags and any new
     * data-param controls Valve adds later. Metadata is cached for a day and retained longer as a
     * stale fallback so a temporary Store outage does not remove the filter UI.
     */
    suspend fun loadFilterCatalog(
        cacheDao: SteamSearchCacheDao,
        forceRefresh: Boolean = false,
    ): FilterCatalogOutcome {
        val now = System.currentTimeMillis()
        val filterCacheKey = "$FILTER_CACHE_KEY_PREFIX:${steamLanguage()}"
        val cachedEntry = cacheDao.get(filterCacheKey)
        val cachedCatalog = cachedEntry?.let { runCatching { decodeFilterCatalog(it.payloadJson) }.getOrNull() }

        if (!forceRefresh && cachedEntry != null && cachedCatalog != null &&
            now - cachedEntry.updatedAt <= FILTER_CACHE_FRESH_MS
        ) {
            cacheDao.touch(filterCacheKey, now)
            return FilterCatalogOutcome(catalog = cachedCatalog, fromCache = true)
        }

        return try {
            val url =
                "https://store.steampowered.com/search/?ignore_preferences=1&ndl=1&l=" +
                    encode(steamLanguage())
            val html = execute(url)
            if (!html.contains("data-param", ignoreCase = true)) {
                error("Steam returned no live filter controls")
            }
            val catalog = SteamStoreSearchParser.parseFilterCatalog(html, fetchedAt = now)
            if (catalog.groups.isEmpty()) error("Steam returned no filter metadata")
            cacheDao.put(
                SteamSearchCacheEntry(
                    cacheKey = filterCacheKey,
                    payloadJson = encodeFilterCatalog(catalog),
                    updatedAt = now,
                    lastAccessedAt = now,
                ),
            )
            maintainCache(cacheDao, now)
            FilterCatalogOutcome(catalog = catalog)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to refresh Steam Store filter metadata")
            if (cachedEntry != null && cachedCatalog != null &&
                now - cachedEntry.updatedAt <= FILTER_CACHE_STALE_MS
            ) {
                cacheDao.touch(filterCacheKey, now)
                FilterCatalogOutcome(
                    catalog = cachedCatalog,
                    fromCache = true,
                    staleCache = true,
                    error = e.message ?: e.javaClass.simpleName,
                )
            } else {
                // The parser's empty input path supplies safe product/platform fallbacks.
                FilterCatalogOutcome(
                    catalog = SteamStoreSearchParser.parseFilterCatalog("", fetchedAt = now),
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    suspend fun clearStoreCache(cacheDao: SteamSearchCacheDao) {
        cacheDao.clear()
    }

    private fun buildSearchUrl(query: String, filters: SteamStoreSearchFilters): String {
        val params = linkedMapOf(
            "query" to "",
            "start" to "0",
            "count" to "50",
            "dynamic_data" to "",
            "sort_by" to filters.sort.parameter,
            "term" to query.trim(),
            "ignore_preferences" to "1",
            "infinite" to "1",
            "json" to "1",
            "ndl" to "1",
            "cc" to steamCountry(),
            "l" to steamLanguage(),
        )

        filters.selected
            .filterValues { it.isNotEmpty() }
            .toSortedMap()
            .forEach { (param, values) ->
                params[param] = values.sorted().joinToString(",")
            }

        if (filters.excludedTagIds.isNotEmpty()) {
            params["untags"] = filters.excludedTagIds.sorted().joinToString(",")
        }
        filters.maxPrice?.takeIf { it.isNotBlank() }?.let { params["maxprice"] = it }
        if (filters.specialsOnly) params["specials"] = "1"
        if (filters.hideFreeToPlay) params["hidef2p"] = "1"

        return buildString {
            append("https://store.steampowered.com/search/results/?")
            append(
                params.entries.joinToString("&") { (key, value) ->
                    "${encode(key)}=${encode(value)}"
                },
            )
        }
    }

    private fun execute(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
            .header("User-Agent", "GameNative-LuaTools")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        storeHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Steam Store HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private suspend fun readSearchCache(
        cacheDao: SteamSearchCacheDao,
        key: String,
        now: Long,
    ): Pair<SteamSearchCacheEntry, SearchOutcome>? {
        val entry = cacheDao.get(key) ?: return null
        if (now - entry.updatedAt > SEARCH_CACHE_STALE_MS) return null
        val decoded = runCatching { decodeSearch(entry.payloadJson) }.getOrNull() ?: return null
        return entry to decoded
    }

    private suspend fun maintainCache(cacheDao: SteamSearchCacheDao, now: Long) {
        runCatching {
            cacheDao.deleteOlderThan(now - CACHE_RETENTION_MS)
            cacheDao.trimTo(CACHE_MAX_ENTRIES)
        }.onFailure {
            Timber.d(it, "Steam Store cache maintenance failed")
        }
    }

    private fun encodeSearch(outcome: SearchOutcome): String {
        val root = JSONObject()
        root.put("total_count", outcome.totalCount)
        val rows = JSONArray()
        outcome.results.forEach { item ->
            rows.put(
                JSONObject()
                    .put("key", item.key)
                    .put("kind", item.kind.name)
                    .put("item_id", item.itemId ?: JSONObject.NULL)
                    .put("name", item.name)
                    .put("store_url", item.storeUrl)
                    .put("image_url", item.imageUrl)
                    .put("release_date", item.releaseDate)
                    .put("price_text", item.priceText),
            )
        }
        root.put("results", rows)
        return root.toString()
    }

    private fun decodeSearch(payload: String): SearchOutcome {
        val root = JSONObject(payload)
        val rows = root.optJSONArray("results") ?: JSONArray()
        val results = buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val kind = runCatching {
                    SteamStoreItemKind.valueOf(row.optString("kind"))
                }.getOrDefault(SteamStoreItemKind.OTHER)
                val itemId = if (row.isNull("item_id")) null else row.optInt("item_id").takeIf { it > 0 }
                val name = row.optString("name")
                val url = row.optString("store_url")
                if (name.isBlank() || url.isBlank()) continue
                add(
                    SteamStoreSearchResult(
                        key = row.optString("key").ifBlank {
                            if (itemId != null) "${kind.name}:$itemId" else "OTHER:$url"
                        },
                        kind = kind,
                        itemId = itemId,
                        name = name,
                        storeUrl = url,
                        imageUrl = row.optString("image_url"),
                        releaseDate = row.optString("release_date"),
                        priceText = row.optString("price_text"),
                    ),
                )
            }
        }
        return SearchOutcome(
            results = results,
            totalCount = root.optInt("total_count", results.size),
        )
    }

    private fun encodeFilterCatalog(catalog: SteamStoreFilterCatalog): String {
        val root = JSONObject().put("fetched_at", catalog.fetchedAt)
        val groups = JSONArray()
        catalog.groups.forEach { group ->
            val options = JSONArray()
            group.options.forEach { option ->
                options.put(
                    JSONObject()
                        .put("param", option.param)
                        .put("value", option.value)
                        .put("label", option.label)
                        .put("group", option.group),
                )
            }
            groups.put(
                JSONObject()
                    .put("key", group.key)
                    .put("title", group.title)
                    .put("options", options),
            )
        }
        root.put("groups", groups)
        return root.toString()
    }

    private fun decodeFilterCatalog(payload: String): SteamStoreFilterCatalog {
        val root = JSONObject(payload)
        val groupsJson = root.optJSONArray("groups") ?: JSONArray()
        val groups = buildList {
            for (groupIndex in 0 until groupsJson.length()) {
                val groupJson = groupsJson.optJSONObject(groupIndex) ?: continue
                val key = groupJson.optString("key")
                val title = groupJson.optString("title")
                val optionsJson = groupJson.optJSONArray("options") ?: JSONArray()
                val options = buildList {
                    for (optionIndex in 0 until optionsJson.length()) {
                        val option = optionsJson.optJSONObject(optionIndex) ?: continue
                        val param = option.optString("param")
                        val value = option.optString("value")
                        val label = option.optString("label")
                        if (param.isBlank() || value.isBlank() || label.isBlank()) continue
                        add(
                            SteamStoreFilterOption(
                                param = param,
                                value = value,
                                label = label,
                                group = option.optString("group").ifBlank { title },
                            ),
                        )
                    }
                }
                if (key.isNotBlank() && options.isNotEmpty()) {
                    add(SteamStoreFilterGroup(key = key, title = title.ifBlank { key }, options = options))
                }
            }
        }
        return SteamStoreFilterCatalog(
            groups = groups,
            fetchedAt = root.optLong("fetched_at"),
        )
    }

    private fun steamLanguage(): String = when (Locale.getDefault().language.lowercase()) {
        "pt" -> if (Locale.getDefault().country.equals("BR", ignoreCase = true)) "brazilian" else "portuguese"
        "zh" -> if (Locale.getDefault().country.equals("TW", ignoreCase = true) ||
            Locale.getDefault().country.equals("HK", ignoreCase = true)
        ) {
            "tchinese"
        } else {
            "schinese"
        }
        "ja" -> "japanese"
        "ko" -> "koreana"
        "de" -> "german"
        "fr" -> "french"
        "es" -> "spanish"
        "it" -> "italian"
        "ru" -> "russian"
        "uk" -> "ukrainian"
        "pl" -> "polish"
        "nl" -> "dutch"
        "sv" -> "swedish"
        "da" -> "danish"
        "no" -> "norwegian"
        "fi" -> "finnish"
        "tr" -> "turkish"
        "cs" -> "czech"
        "hu" -> "hungarian"
        "ro" -> "romanian"
        "th" -> "thai"
        "vi" -> "vietnamese"
        "id" -> "indonesian"
        else -> "english"
    }

    private fun steamCountry(): String =
        Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase() ?: "US"

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    /**
     * PICS cache enrichment. Unlike the old implementation this keeps every named app type,
     * not only AppType.game, so locally-cached Steam software/DLC/tools remain searchable too.
     */
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
                    if (existingCount > 0) PrefManager.steamCatalogChangeNumber = changes.currentChangeNumber
                    _syncState.value = SyncState(isSyncing = false, indexedGames = existingCount)
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

                        val productInfo = steamApps.picsGetProductInfo(
                            apps = batch.map { change ->
                                PICSRequest(id = change.id, accessToken = tokens[change.id] ?: 0L)
                            },
                            packages = emptyList(),
                        ).await()

                        val rows = mutableListOf<SteamCatalogEntry>()
                        productInfo.results.forEach { result ->
                            result.apps.values.forEach { app ->
                                val parsed = app.keyValues.generateSteamApp()
                                if (parsed.name.isNotBlank()) {
                                    rows += SteamCatalogEntry(
                                        appId = app.id,
                                        name = parsed.name,
                                        lastChangeNumber = app.changeNumber,
                                        iconHash = parsed.clientIconHash.ifBlank { parsed.iconHash },
                                    )
                                }
                            }
                        }
                        if (rows.isNotEmpty()) dao.insertAll(rows)
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
                if (!failed) PrefManager.steamCatalogChangeNumber = changes.currentChangeNumber
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
