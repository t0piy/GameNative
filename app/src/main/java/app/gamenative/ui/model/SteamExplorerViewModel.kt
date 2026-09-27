package app.gamenative.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamCatalogEntry
import app.gamenative.db.dao.SteamCatalogDao
import app.gamenative.service.SteamService
import app.gamenative.steam.SteamCatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SteamExplorerViewModel @Inject constructor(
    private val steamCatalogDao: SteamCatalogDao,
) : ViewModel() {
    data class UiState(
        val query: String = "",
        val games: List<SteamCatalogEntry> = emptyList(),
        val catalogSize: Int = 0,
        val syncState: SteamCatalogRepository.SyncState = SteamCatalogRepository.SyncState(),
        val openingAppId: Int? = null,
    )

    private val query = MutableStateFlow("")
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        collectSearch()
        viewModelScope.launch {
            steamCatalogDao.observeCount().collect { count ->
                _state.update { it.copy(catalogSize = count) }
            }
        }
        viewModelScope.launch {
            SteamCatalogRepository.syncState.collect { sync ->
                _state.update { it.copy(syncState = sync) }
            }
        }
    }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun collectSearch() {
        viewModelScope.launch {
            query
                .debounce(250)
                .distinctUntilChanged()
                .flatMapLatest { steamCatalogDao.search(it.trim()) }
                .collect { games ->
                    _state.update { it.copy(games = games) }
                }
        }
    }

    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value) }
        query.value = value
    }

    fun refresh(forceFull: Boolean = false) {
        SteamService.refreshSteamCatalog(forceFull)
    }

    fun open(entry: SteamCatalogEntry, onReady: (LibraryItem) -> Unit) {
        if (_state.value.openingAppId != null) return
        _state.update { it.copy(openingAppId = entry.appId) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                SteamService.hydratePublicAppInfo(entry.appId)
            }
            _state.update { it.copy(openingAppId = null) }
            onReady(entry.toLibraryItem())
        }
    }

    private fun SteamCatalogEntry.toLibraryItem(): LibraryItem = LibraryItem(
        appId = "${GameSource.STEAM.name}_$appId",
        name = name,
        iconHash = iconHash,
        capsuleImageUrl = capsuleUrl,
        headerImageUrl = headerUrl,
        heroImageUrl = heroUrl,
        gameSource = GameSource.STEAM,
        isInstalled = SteamService.isAppInstalled(appId),
    )
}
