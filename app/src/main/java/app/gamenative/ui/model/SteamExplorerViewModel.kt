package app.gamenative.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamApp
import app.gamenative.data.SteamCatalogEntry
import app.gamenative.data.SteamManualEntry
import app.gamenative.db.dao.SteamCatalogDao
import app.gamenative.db.dao.SteamSearchCacheDao
import app.gamenative.db.dao.SteamManualEntryDao
import app.gamenative.enums.OS
import app.gamenative.service.SteamService
import app.gamenative.steam.SteamCatalogRepository
import app.gamenative.steam.SteamStoreAppDetails
import app.gamenative.steam.SteamStoreFilterCatalog
import app.gamenative.steam.SteamStoreFilterOption
import app.gamenative.steam.SteamStoreSearchFilters
import app.gamenative.steam.SteamStoreSearchResult
import app.gamenative.steam.SteamStorePlatformSupport
import app.gamenative.steam.SteamStoreSort
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SteamExplorerViewModel @Inject constructor(
    private val steamCatalogDao: SteamCatalogDao,
    private val steamSearchCacheDao: SteamSearchCacheDao,
    private val steamManualEntryDao: SteamManualEntryDao,
) : ViewModel() {
    data class UiState(
        val query: String = "",
        val results: List<SteamStoreSearchResult> = emptyList(),
        val totalResults: Int = 0,
        val filters: SteamStoreSearchFilters = SteamStoreSearchFilters(),
        val filterCatalog: SteamStoreFilterCatalog = SteamStoreFilterCatalog(),
        val filterCatalogLoading: Boolean = true,
        val searchLoading: Boolean = false,
        val searchFromCache: Boolean = false,
        val staleCache: Boolean = false,
        val error: String? = null,
        val filterError: String? = null,
        val manualResults: List<SteamStoreSearchResult> = emptyList(),
        val manualAppIds: Set<Int> = emptySet(),
        val manualAdding: Boolean = false,
        val manualError: String? = null,
        val selectedResult: SteamStoreSearchResult? = null,
        val details: SteamStoreAppDetails? = null,
        val detailsLoading: Boolean = false,
        val detailsFromCache: Boolean = false,
        val detailsStaleCache: Boolean = false,
        val detailsError: String? = null,
        val openingAppId: Int? = null,
    ) {
        val displayedResults: List<SteamStoreSearchResult>
            get() {
                val normalizedQuery = query.trim()
                val manualVisible = if (normalizedQuery.isBlank()) {
                    manualResults
                } else {
                    manualResults.filter { result ->
                        result.name.contains(normalizedQuery, ignoreCase = true) ||
                            result.appId?.toString() == normalizedQuery
                    }
                }
                val manualKeys = manualVisible.mapTo(mutableSetOf()) { it.key }
                return manualVisible + results.filterNot { it.key in manualKeys }
            }
    }

    private val query = MutableStateFlow("")
    private val filters = MutableStateFlow(SteamStoreSearchFilters())
    private val refreshVersion = MutableStateFlow(0)
    private val refreshCounter = AtomicInteger(0)
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        loadFilterCatalog()
        collectSearch()
        collectManualEntries()
    }

    private fun collectManualEntries() {
        viewModelScope.launch {
            steamManualEntryDao.observeAll().collect { entries ->
                val results = entries.map { entry ->
                    SteamStoreSearchResult(
                        key = "APP:${entry.appId}",
                        kind = app.gamenative.steam.SteamStoreItemKind.APP,
                        itemId = entry.appId,
                        name = entry.name,
                        storeUrl = "https://store.steampowered.com/app/${entry.appId}/",
                        imageUrl = entry.imageUrl,
                    )
                }
                _state.update {
                    it.copy(
                        manualResults = results,
                        manualAppIds = entries.mapTo(mutableSetOf()) { entry -> entry.appId },
                    )
                }
            }
        }
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun collectSearch() {
        viewModelScope.launch {
            var handledRefreshVersion = 0
            combine(
                query.debounce(300).distinctUntilChanged(),
                filters,
                refreshVersion,
            ) { currentQuery, currentFilters, version ->
                Triple(currentQuery, currentFilters, version)
            }.collectLatest { (currentQuery, currentFilters, version) ->
                val forceRefresh = version != handledRefreshVersion
                handledRefreshVersion = version
                runSearch(currentQuery, currentFilters, forceRefresh)
            }
        }
    }

    private suspend fun runSearch(
        currentQuery: String,
        currentFilters: SteamStoreSearchFilters,
        forceRefresh: Boolean,
    ) {
        _state.update {
            it.copy(
                searchLoading = true,
                error = null,
                filters = currentFilters,
            )
        }

        val outcome = withContext(Dispatchers.IO) {
            SteamCatalogRepository.searchStore(
                query = currentQuery,
                filters = currentFilters,
                cacheDao = steamSearchCacheDao,
                forceRefresh = forceRefresh,
            )
        }

        val appRows = outcome.results.mapNotNull { result ->
            val appId = result.appId ?: return@mapNotNull null
            SteamCatalogEntry(appId = appId, name = result.name)
        }
        if (appRows.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                steamCatalogDao.insertAll(appRows)
            }
        }

        _state.update {
            it.copy(
                results = outcome.results,
                totalResults = outcome.totalCount,
                searchLoading = false,
                searchFromCache = outcome.fromCache,
                staleCache = outcome.staleCache,
                error = outcome.error,
                filters = currentFilters,
            )
        }
    }

    private fun loadFilterCatalog(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(filterCatalogLoading = true, filterError = null) }
            val outcome = withContext(Dispatchers.IO) {
                SteamCatalogRepository.loadFilterCatalog(
                    cacheDao = steamSearchCacheDao,
                    forceRefresh = forceRefresh,
                )
            }
            _state.update {
                it.copy(
                    filterCatalog = outcome.catalog,
                    filterCatalogLoading = false,
                    filterError = outcome.error,
                )
            }
        }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        query.value = value
    }

    fun refresh() {
        refreshVersion.value = refreshCounter.incrementAndGet()
        loadFilterCatalog(forceRefresh = true)
    }

    fun toggleOption(option: SteamStoreFilterOption) {
        if (option.param == "tags") {
            cycleTag(option.value)
            return
        }
        val next = filters.value.toggle(option)
        filters.value = next
        _state.update { it.copy(filters = next) }
    }

    /**
     * Tag state cycles Off -> Include -> Exclude -> Off, mirroring Steam's include/not controls.
     */
    fun cycleTag(tagId: String) {
        val next = filters.value.cycleTag(tagId)
        filters.value = next
        _state.update { it.copy(filters = next) }
    }

    fun setSort(sort: SteamStoreSort) {
        val next = filters.value.copy(sort = sort)
        filters.value = next
        _state.update { it.copy(filters = next) }
    }

    fun setMaxPrice(value: String?) {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() }
        val next = filters.value.copy(maxPrice = normalized)
        filters.value = next
        _state.update { it.copy(filters = next) }
    }

    fun setSpecialsOnly(value: Boolean) {
        val next = filters.value.copy(specialsOnly = value)
        filters.value = next
        _state.update { it.copy(filters = next) }
    }

    fun setHideFreeToPlay(value: Boolean) {
        val next = filters.value.copy(hideFreeToPlay = value)
        filters.value = next
        _state.update { it.copy(filters = next) }
    }

    fun clearFilters() {
        val next = SteamStoreSearchFilters()
        filters.value = next
        _state.update { it.copy(filters = next) }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            SteamCatalogRepository.clearStoreCache(steamSearchCacheDao)
            withContext(Dispatchers.Main) {
                refresh()
            }
        }
    }

    fun selectResult(result: SteamStoreSearchResult) {
        _state.update {
            it.copy(
                selectedResult = result,
                details = null,
                detailsLoading = result.appId != null,
                detailsFromCache = false,
                detailsStaleCache = false,
                detailsError = null,
            )
        }

        val appId = result.appId ?: return
        viewModelScope.launch {
            val isManual = appId in _state.value.manualAppIds
            val outcome = withContext(Dispatchers.IO) {
                if (isManual) {
                    SteamCatalogRepository.loadManualAppDetails(
                        appId = appId,
                        cacheDao = steamSearchCacheDao,
                    )
                } else {
                    SteamCatalogRepository.loadAppDetails(
                        appId = appId,
                        cacheDao = steamSearchCacheDao,
                    )
                }
            }
            val pics = withContext(Dispatchers.IO) {
                if (isManual && SteamService.isLoggedIn) {
                    SteamService.hydratePublicAppInfo(appId)
                } else {
                    SteamService.getAppInfoOf(appId)
                }
            }
            val mergedDetails = mergeStoreAndPics(appId, outcome.details, pics)
            if (_state.value.selectedResult?.appId != appId) return@launch
            _state.update {
                it.copy(
                    details = mergedDetails,
                    detailsLoading = false,
                    detailsFromCache = outcome.fromCache,
                    detailsStaleCache = outcome.staleCache,
                    detailsError = outcome.error,
                )
            }
        }
    }

    fun refreshSelectedDetails() {
        val appId = _state.value.selectedResult?.appId ?: return
        _state.update { it.copy(detailsLoading = true, detailsError = null) }
        viewModelScope.launch {
            val isManual = appId in _state.value.manualAppIds
            val outcome = withContext(Dispatchers.IO) {
                if (isManual) {
                    SteamCatalogRepository.loadManualAppDetails(
                        appId = appId,
                        cacheDao = steamSearchCacheDao,
                        forceRefresh = true,
                    )
                } else {
                    SteamCatalogRepository.loadAppDetails(
                        appId = appId,
                        cacheDao = steamSearchCacheDao,
                        forceRefresh = true,
                    )
                }
            }
            val pics = withContext(Dispatchers.IO) {
                if (isManual && SteamService.isLoggedIn) {
                    SteamService.hydratePublicAppInfo(appId)
                } else {
                    SteamService.getAppInfoOf(appId)
                }
            }
            val mergedDetails = mergeStoreAndPics(appId, outcome.details, pics)
            if (_state.value.selectedResult?.appId != appId) return@launch
            _state.update {
                it.copy(
                    details = mergedDetails,
                    detailsLoading = false,
                    detailsFromCache = outcome.fromCache,
                    detailsStaleCache = outcome.staleCache,
                    detailsError = outcome.error,
                )
            }
        }
    }

    fun addManualAppId(rawValue: String) {
        val appId = rawValue.trim().toIntOrNull()
        if (appId == null || appId <= 0) {
            _state.update { it.copy(manualError = "Invalid Steam AppID") }
            return
        }
        if (_state.value.manualAdding) return

        _state.update { it.copy(manualAdding = true, manualError = null) }
        viewModelScope.launch {
            val storeOutcome = withContext(Dispatchers.IO) {
                SteamCatalogRepository.loadManualAppDetails(
                    appId = appId,
                    cacheDao = steamSearchCacheDao,
                    forceRefresh = true,
                )
            }

            val pics = withContext(Dispatchers.IO) {
                if (SteamService.isLoggedIn) {
                    SteamService.hydratePublicAppInfo(appId)
                } else {
                    SteamService.getAppInfoOf(appId)
                }
            }

            val details = mergeStoreAndPics(appId, storeOutcome.details, pics)
            val name = details?.name?.takeIf { it.isNotBlank() }
                ?: pics?.name?.takeIf { it.isNotBlank() }
                ?: "AppID $appId"
            val imageUrl = details?.capsuleImage?.takeIf { it.isNotBlank() }
                ?: details?.headerImage?.takeIf { it.isNotBlank() }
                ?: pics?.getCapsuleUrl(large = true)?.takeIf { it.isNotBlank() }
                ?: pics?.getCapsuleUrl()?.takeIf { it.isNotBlank() }
                ?: "https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/header.jpg"

            withContext(Dispatchers.IO) {
                steamManualEntryDao.put(
                    SteamManualEntry(
                        appId = appId,
                        name = name,
                        imageUrl = imageUrl,
                    ),
                )
                steamCatalogDao.insertAll(
                    listOf(
                        SteamCatalogEntry(
                            appId = appId,
                            name = name,
                            iconHash = pics?.clientIconHash?.ifBlank { pics.iconHash }.orEmpty(),
                        ),
                    ),
                )
            }

            val result = SteamStoreSearchResult(
                key = "APP:$appId",
                kind = app.gamenative.steam.SteamStoreItemKind.APP,
                itemId = appId,
                name = name,
                storeUrl = "https://store.steampowered.com/app/$appId/",
                imageUrl = imageUrl,
            )

            _state.update {
                it.copy(
                    manualAdding = false,
                    manualError = if (details == null && pics == null) {
                        "AppID added, but Steam returned only limited public metadata."
                    } else {
                        null
                    },
                    selectedResult = result,
                    details = details,
                    detailsLoading = false,
                    detailsFromCache = storeOutcome.fromCache,
                    detailsStaleCache = storeOutcome.staleCache,
                    detailsError = if (details == null) storeOutcome.error else null,
                )
            }
        }
    }

    fun removeManualAppId(appId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            steamManualEntryDao.delete(appId)
        }
        if (_state.value.selectedResult?.appId == appId) {
            closeDetails()
        }
    }

    fun closeDetails() {
        _state.update {
            it.copy(
                selectedResult = null,
                details = null,
                detailsLoading = false,
                detailsFromCache = false,
                detailsStaleCache = false,
                detailsError = null,
            )
        }
    }

    private fun mergeStoreAndPics(
        appId: Int,
        store: SteamStoreAppDetails?,
        pics: SteamApp?,
    ): SteamStoreAppDetails? {
        if (store == null && pics == null) return null
        val base = store ?: SteamStoreAppDetails(appId = appId)

        val picsPlatforms = pics?.let { app ->
            SteamStorePlatformSupport(
                windows = OS.windows in app.osList,
                mac = OS.macos in app.osList,
                linux = OS.linux in app.osList,
            )
        }

        return base.copy(
            type = base.type.ifBlank { pics?.type?.name.orEmpty() },
            name = base.name.ifBlank { pics?.name.orEmpty() },
            isFree = base.isFree || pics?.isFreeApp == true,
            headerImage = base.headerImage.ifBlank {
                pics?.getHeaderImageUrl().orEmpty().ifBlank { pics?.headerUrl.orEmpty() }
            },
            capsuleImage = base.capsuleImage.ifBlank {
                pics?.getCapsuleUrl(large = true).orEmpty().ifBlank {
                    pics?.getCapsuleUrl().orEmpty()
                }
            },
            website = base.website.ifBlank { pics?.homepageUrl.orEmpty() },
            developers = base.developers.ifEmpty {
                pics?.developer?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
            },
            publishers = base.publishers.ifEmpty {
                pics?.publisher?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
            },
            platforms = if (
                base.platforms.windows || base.platforms.mac || base.platforms.linux
            ) {
                base.platforms
            } else {
                picsPlatforms ?: base.platforms
            },
            metacriticScore = base.metacriticScore
                ?: pics?.metacriticScore?.toInt()?.takeIf { it > 0 },
            metacriticUrl = base.metacriticUrl.ifBlank { pics?.metacriticFullUrl.orEmpty() },
        )
    }

    fun openApp(result: SteamStoreSearchResult, onReady: (LibraryItem) -> Unit) {
        val appId = result.appId ?: return
        if (_state.value.openingAppId != null) return
        _state.update { it.copy(openingAppId = appId) }
        viewModelScope.launch {
            val hydrated = withContext(Dispatchers.IO) {
                val app = SteamService.hydratePublicAppInfo(appId)
                if (app != null) {
                    SteamService.hydratePublicDlcMetadata(appId)
                }
                app
            }
            _state.update { it.copy(openingAppId = null) }
            val iconHash = hydrated
                ?.let { app -> app.clientIconHash.ifBlank { app.iconHash } }
                .orEmpty()
            onReady(
                LibraryItem(
                    appId = "${GameSource.STEAM.name}_$appId",
                    name = hydrated?.name?.takeIf { it.isNotBlank() } ?: result.name,
                    iconHash = iconHash,
                    capsuleImageUrl = hydrated?.getCapsuleUrl().orEmpty().ifBlank { result.imageUrl },
                    headerImageUrl = hydrated?.headerUrl.orEmpty().ifBlank { result.imageUrl },
                    heroImageUrl = hydrated?.getHeroUrl().orEmpty().ifBlank { result.imageUrl },
                    gameSource = GameSource.STEAM,
                    isInstalled = SteamService.isAppInstalled(appId),
                ),
            )
        }
    }
}
