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

    data class AppDetailsOutcome(
        val details: SteamStoreAppDetails? = null,
        val fromCache: Boolean = false,
        val staleCache: Boolean = false,
        val error: String? = null,
    )

    private const val BATCH_SIZE = 128
    private const val SEARCH_CACHE_FRESH_MS = 10 * 60 * 1000L
    private const val SEARCH_CACHE_STALE_MS = 7 * 24 * 60 * 60 * 1000L
    private const val FILTER_CACHE_FRESH_MS = 24 * 60 * 60 * 1000L
    private const val DETAILS_CACHE_FRESH_MS = 6 * 60 * 60 * 1000L
    private const val FILTER_CACHE_STALE_MS = 14 * 24 * 60 * 60 * 1000L
    private const val DETAILS_CACHE_STALE_MS = 14 * 24 * 60 * 60 * 1000L
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
                "https://store.steampowered.com/search/?ignore_preferences=1&show_all=1&ndl=1&l=" +
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

    suspend fun loadAppDetails(
        appId: Int,
        cacheDao: SteamSearchCacheDao,
        forceRefresh: Boolean = false,
    ): AppDetailsOutcome {
        if (appId <= 0) return AppDetailsOutcome(error = "Invalid AppID")

        val now = System.currentTimeMillis()
        val cacheKey = "steam-store:appdetails:v1:${steamCountry()}:${steamLanguage()}:$appId"
        val cachedEntry = cacheDao.get(cacheKey)
        val cachedDetails = cachedEntry?.let {
            runCatching { decodeAppDetails(it.payloadJson) }.getOrNull()
        }

        if (!forceRefresh && cachedEntry != null && cachedDetails != null &&
            now - cachedEntry.updatedAt <= DETAILS_CACHE_FRESH_MS
        ) {
            cacheDao.touch(cacheKey, now)
            return AppDetailsOutcome(details = cachedDetails, fromCache = true)
        }

        return try {
            val url =
                "https://store.steampowered.com/api/appdetails?appids=$appId" +
                    "&cc=${encode(steamCountry())}&l=${encode(steamLanguage())}"
            val body = execute(url)
            val root = JSONObject(body)
            val wrapper = root.optJSONObject(appId.toString())
                ?: error("Steam Store returned no details for AppID $appId")
            if (!wrapper.optBoolean("success", false)) {
                error("Steam Store could not load AppID $appId")
            }
            val data = wrapper.optJSONObject("data")
                ?: error("Steam Store returned empty details for AppID $appId")
            val details = parseAppDetails(appId, data)

            cacheDao.put(
                SteamSearchCacheEntry(
                    cacheKey = cacheKey,
                    payloadJson = encodeAppDetails(details),
                    updatedAt = now,
                    lastAccessedAt = now,
                ),
            )
            maintainCache(cacheDao, now)
            AppDetailsOutcome(details = details)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Steam Store appdetails failed for $appId")
            if (cachedEntry != null && cachedDetails != null &&
                now - cachedEntry.updatedAt <= DETAILS_CACHE_STALE_MS
            ) {
                cacheDao.touch(cacheKey, now)
                AppDetailsOutcome(
                    details = cachedDetails,
                    fromCache = true,
                    staleCache = true,
                    error = e.message ?: e.javaClass.simpleName,
                )
            } else {
                AppDetailsOutcome(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    suspend fun clearStoreCache(cacheDao: SteamSearchCacheDao) {
        cacheDao.clear()
    }

    private fun buildSearchUrl(query: String, filters: SteamStoreSearchFilters): String {
        val params = mutableListOf(
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
                if (param.endsWith("[]")) {
                    values.sorted().forEach { value -> params += param to value }
                } else {
                    params += param to values.sorted().joinToString(",")
                }
            }

        if (filters.excludedTagIds.isNotEmpty()) {
            params += "untags" to filters.excludedTagIds.sorted().joinToString(",")
        }
        filters.maxPrice?.takeIf { it.isNotBlank() }?.let { params += "maxprice" to it }
        if (filters.specialsOnly) params += "specials" to "1"
        if (filters.hideFreeToPlay) params += "hidef2p" to "1"

        return buildString {
            append("https://store.steampowered.com/search/results/?")
            append(
                params.joinToString("&") { (key, value) ->
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

    private fun parseAppDetails(appId: Int, data: JSONObject): SteamStoreAppDetails {
        fun stringList(key: String): List<String> {
            val array = data.optJSONArray(key) ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index).trim()
                    if (value.isNotBlank()) add(value)
                }
            }
        }

        fun namedValues(key: String): List<SteamStoreNamedValue> {
            val array = data.optJSONArray(key) ?: return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val description = item.optString("description").trim()
                    if (description.isNotBlank()) {
                        add(SteamStoreNamedValue(item.optInt("id"), description))
                    }
                }
            }
        }

        fun requirements(key: String): Pair<String, String> {
            val obj = data.optJSONObject(key) ?: return "" to ""
            return stripHtml(obj.optString("minimum")) to stripHtml(obj.optString("recommended"))
        }

        val priceObj = data.optJSONObject("price_overview")
        val price = priceObj?.let {
            SteamStorePriceOverview(
                currency = it.optString("currency"),
                initialFormatted = it.optString("initial_formatted"),
                finalFormatted = it.optString("final_formatted"),
                discountPercent = it.optInt("discount_percent"),
            )
        }

        val platformsObj = data.optJSONObject("platforms")
        val platforms = SteamStorePlatformSupport(
            windows = platformsObj?.optBoolean("windows") == true,
            mac = platformsObj?.optBoolean("mac") == true,
            linux = platformsObj?.optBoolean("linux") == true,
        )

        val metacriticObj = data.optJSONObject("metacritic")
        val recommendationsObj = data.optJSONObject("recommendations")
        val achievementsObj = data.optJSONObject("achievements")
        val releaseDateObj = data.optJSONObject("release_date")
        val supportInfoObj = data.optJSONObject("support_info")
        val (pcMin, pcRec) = requirements("pc_requirements")
        val (macMin, macRec) = requirements("mac_requirements")
        val (linuxMin, linuxRec) = requirements("linux_requirements")

        val screenshots = buildList {
            val array = data.optJSONArray("screenshots") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    SteamStoreScreenshot(
                        id = item.optInt("id"),
                        thumbnailUrl = item.optString("path_thumbnail"),
                        fullUrl = item.optString("path_full"),
                    ),
                )
            }
        }

        return SteamStoreAppDetails(
            appId = appId,
            type = data.optString("type"),
            name = data.optString("name"),
            requiredAge = data.optString("required_age"),
            isFree = data.optBoolean("is_free"),
            shortDescription = stripHtml(data.optString("short_description")),
            detailedDescription = stripHtml(data.optString("detailed_description")),
            aboutTheGame = stripHtml(data.optString("about_the_game")),
            supportedLanguages = stripHtml(data.optString("supported_languages")),
            headerImage = data.optString("header_image"),
            capsuleImage = data.optString("capsule_image"),
            capsuleImageV5 = data.optString("capsule_imagev5"),
            website = data.optString("website"),
            developers = stringList("developers"),
            publishers = stringList("publishers"),
            price = price,
            platforms = platforms,
            metacriticScore = metacriticObj?.optInt("score")?.takeIf { it > 0 },
            metacriticUrl = metacriticObj?.optString("url").orEmpty(),
            categories = namedValues("categories"),
            genres = namedValues("genres"),
            recommendationsTotal = recommendationsObj?.optInt("total")?.takeIf { it > 0 },
            achievementsTotal = achievementsObj?.optInt("total")?.takeIf { it > 0 },
            releaseDate = releaseDateObj?.optString("date").orEmpty(),
            comingSoon = releaseDateObj?.optBoolean("coming_soon") == true,
            supportUrl = supportInfoObj?.optString("url").orEmpty(),
            supportEmail = supportInfoObj?.optString("email").orEmpty(),
            pcRequirementsMinimum = pcMin,
            pcRequirementsRecommended = pcRec,
            macRequirementsMinimum = macMin,
            macRequirementsRecommended = macRec,
            linuxRequirementsMinimum = linuxMin,
            linuxRequirementsRecommended = linuxRec,
            screenshots = screenshots,
        )
    }

    private fun encodeAppDetails(details: SteamStoreAppDetails): String = JSONObject()
        .put("app_id", details.appId)
        .put("type", details.type)
        .put("name", details.name)
        .put("required_age", details.requiredAge)
        .put("is_free", details.isFree)
        .put("short_description", details.shortDescription)
        .put("detailed_description", details.detailedDescription)
        .put("about_the_game", details.aboutTheGame)
        .put("supported_languages", details.supportedLanguages)
        .put("header_image", details.headerImage)
        .put("capsule_image", details.capsuleImage)
        .put("capsule_imagev5", details.capsuleImageV5)
        .put("website", details.website)
        .put("developers", JSONArray(details.developers))
        .put("publishers", JSONArray(details.publishers))
        .put(
            "price",
            details.price?.let {
                JSONObject()
                    .put("currency", it.currency)
                    .put("initial_formatted", it.initialFormatted)
                    .put("final_formatted", it.finalFormatted)
                    .put("discount_percent", it.discountPercent)
            } ?: JSONObject.NULL,
        )
        .put(
            "platforms",
            JSONObject()
                .put("windows", details.platforms.windows)
                .put("mac", details.platforms.mac)
                .put("linux", details.platforms.linux),
        )
        .put("metacritic_score", details.metacriticScore ?: JSONObject.NULL)
        .put("metacritic_url", details.metacriticUrl)
        .put("categories", encodeNamedValues(details.categories))
        .put("genres", encodeNamedValues(details.genres))
        .put("recommendations_total", details.recommendationsTotal ?: JSONObject.NULL)
        .put("achievements_total", details.achievementsTotal ?: JSONObject.NULL)
        .put("release_date", details.releaseDate)
        .put("coming_soon", details.comingSoon)
        .put("support_url", details.supportUrl)
        .put("support_email", details.supportEmail)
        .put("pc_min", details.pcRequirementsMinimum)
        .put("pc_rec", details.pcRequirementsRecommended)
        .put("mac_min", details.macRequirementsMinimum)
        .put("mac_rec", details.macRequirementsRecommended)
        .put("linux_min", details.linuxRequirementsMinimum)
        .put("linux_rec", details.linuxRequirementsRecommended)
        .put(
            "screenshots",
            JSONArray().apply {
                details.screenshots.forEach { shot ->
                    put(
                        JSONObject()
                            .put("id", shot.id)
                            .put("thumbnail", shot.thumbnailUrl)
                            .put("full", shot.fullUrl),
                    )
                }
            },
        )
        .toString()

    private fun decodeAppDetails(payload: String): SteamStoreAppDetails {
        val root = JSONObject(payload)
        val priceObj = root.optJSONObject("price")
        val platformsObj = root.optJSONObject("platforms")
        val screenshotsJson = root.optJSONArray("screenshots") ?: JSONArray()
        val screenshots = buildList {
            for (index in 0 until screenshotsJson.length()) {
                val shot = screenshotsJson.optJSONObject(index) ?: continue
                add(
                    SteamStoreScreenshot(
                        id = shot.optInt("id"),
                        thumbnailUrl = shot.optString("thumbnail"),
                        fullUrl = shot.optString("full"),
                    ),
                )
            }
        }

        return SteamStoreAppDetails(
            appId = root.optInt("app_id"),
            type = root.optString("type"),
            name = root.optString("name"),
            requiredAge = root.optString("required_age"),
            isFree = root.optBoolean("is_free"),
            shortDescription = root.optString("short_description"),
            detailedDescription = root.optString("detailed_description"),
            aboutTheGame = root.optString("about_the_game"),
            supportedLanguages = root.optString("supported_languages"),
            headerImage = root.optString("header_image"),
            capsuleImage = root.optString("capsule_image"),
            capsuleImageV5 = root.optString("capsule_imagev5"),
            website = root.optString("website"),
            developers = decodeStringArray(root.optJSONArray("developers")),
            publishers = decodeStringArray(root.optJSONArray("publishers")),
            price = priceObj?.let {
                SteamStorePriceOverview(
                    currency = it.optString("currency"),
                    initialFormatted = it.optString("initial_formatted"),
                    finalFormatted = it.optString("final_formatted"),
                    discountPercent = it.optInt("discount_percent"),
                )
            },
            platforms = SteamStorePlatformSupport(
                windows = platformsObj?.optBoolean("windows") == true,
                mac = platformsObj?.optBoolean("mac") == true,
                linux = platformsObj?.optBoolean("linux") == true,
            ),
            metacriticScore = if (root.isNull("metacritic_score")) null else root.optInt("metacritic_score"),
            metacriticUrl = root.optString("metacritic_url"),
            categories = decodeNamedValues(root.optJSONArray("categories")),
            genres = decodeNamedValues(root.optJSONArray("genres")),
            recommendationsTotal = if (root.isNull("recommendations_total")) null else root.optInt("recommendations_total"),
            achievementsTotal = if (root.isNull("achievements_total")) null else root.optInt("achievements_total"),
            releaseDate = root.optString("release_date"),
            comingSoon = root.optBoolean("coming_soon"),
            supportUrl = root.optString("support_url"),
            supportEmail = root.optString("support_email"),
            pcRequirementsMinimum = root.optString("pc_min"),
            pcRequirementsRecommended = root.optString("pc_rec"),
            macRequirementsMinimum = root.optString("mac_min"),
            macRequirementsRecommended = root.optString("mac_rec"),
            linuxRequirementsMinimum = root.optString("linux_min"),
            linuxRequirementsRecommended = root.optString("linux_rec"),
            screenshots = screenshots,
        )
    }

    private fun encodeNamedValues(values: List<SteamStoreNamedValue>): JSONArray = JSONArray().apply {
        values.forEach { value ->
            put(JSONObject().put("id", value.id).put("description", value.description))
        }
    }

    private fun decodeNamedValues(array: JSONArray?): List<SteamStoreNamedValue> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val description = item.optString("description")
                if (description.isNotBlank()) add(SteamStoreNamedValue(item.optInt("id"), description))
            }
        }
    }

    private fun decodeStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }
    }

    private fun stripHtml(value: String): String = value
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "• ")
        .replace(Regex("</li>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

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
