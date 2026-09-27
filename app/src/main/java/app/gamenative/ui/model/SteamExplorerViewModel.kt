package app.gamenative.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamCatalogEntry
import app.gamenative.db.dao.SteamCatalogDao
import app.gamenative.db.dao.SteamSearchCacheDao
import app.gamenative.service.SteamService
import app.gamenative.steam.SteamCatalogRepository
import app.gamenative.steam.SteamStoreAppDetails
import app.gamenative.steam.SteamStoreFilterCatalog
import app.gamenative.steam.SteamStoreFilterOption
import app.gamenative.steam.SteamStoreSearchFilters
import app.gamenative.steam.SteamStoreSearchResult
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
        val selectedResult: SteamStoreSearchResult? = null,
        val details: SteamStoreAppDetails? = null,
        val detailsLoading: Boolean = false,
        val detailsFromCache: Boolean = false,
        val detailsStaleCache: Boolean = false,
        val detailsError: String? = null,
        val openingAppId: Int? = null,
    )

    private val query = MutableStateFlow("")
    private val filters = MutableStateFlow(SteamStoreSearchFilters())
    private val refreshVersion = MutableStateFlow(0)
    private val refreshCounter = AtomicInteger(0)
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        loadFilterCatalog()
        collectSearch()
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
            val outcome = withContext(Dispatchers.IO) {
                SteamCatalogRepository.loadAppDetails(
                    appId = appId,
                    cacheDao = steamSearchCacheDao,
                )
            }
            if (_state.value.selectedResult?.appId != appId) return@launch
            _state.update {
                it.copy(
                    details = outcome.details,
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
            val outcome = withContext(Dispatchers.IO) {
                SteamCatalogRepository.loadAppDetails(
                    appId = appId,
                    cacheDao = steamSearchCacheDao,
                    forceRefresh = true,
                )
            }
            if (_state.value.selectedResult?.appId != appId) return@launch
            _state.update {
                it.copy(
                    details = outcome.details,
                    detailsLoading = false,
                    detailsFromCache = outcome.fromCache,
                    detailsStaleCache = outcome.staleCache,
                    detailsError = outcome.error,
                )
            }
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

    fun openApp(result: SteamStoreSearchResult, onReady: (LibraryItem) -> Unit) {
        val appId = result.appId ?: return
        if (_state.value.openingAppId != null) return
        _state.update { it.copy(openingAppId = appId) }
        viewModelScope.launch {
            val hydrated = withContext(Dispatchers.IO) {
                SteamService.hydratePublicAppInfo(appId)
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
