package app.gamenative.ui.screen.library

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.FeaturedItem
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.RecommendationRepository
import app.gamenative.data.gog.GogRecCard
import app.gamenative.service.SteamService
import app.gamenative.steam.SteamStoreAppDetails
import app.gamenative.steam.SteamStoreFilterOption
import app.gamenative.steam.SteamStoreItemKind
import app.gamenative.steam.SteamStoreSort
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.model.GogRecommendationsViewModel
import app.gamenative.ui.model.SteamExplorerViewModel
import app.gamenative.ui.screen.library.components.LibraryCarouselPane
import app.gamenative.ui.screen.library.components.LibraryListPane
import app.gamenative.utils.ConversionTracker
import com.posthog.PostHog
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import java.util.EnumSet
import kotlinx.coroutines.delay
import timber.log.Timber

private enum class ExplorerSource {
    GOG,
    STEAM,
}

@Composable
fun RecommendedTabPane(
    currentPaneType: PaneType,
    onNavigate: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
    gogEnabled: Boolean = true,
    steamAvailable: Boolean = SteamService.isLoggedIn,
    onRequestGogConsent: () -> Unit = {},
    viewModel: GogRecommendationsViewModel = hiltViewModel(),
    steamViewModel: SteamExplorerViewModel = hiltViewModel(),
    firstCarouselItemFocusRequester: FocusRequester? = null,
    firstGridItemFocusRequester: FocusRequester? = null,
    focusTargetListIndex: Int = 0,
    onFocusedIndexChanged: (Int) -> Unit = {},
    onItemCountChanged: (Int) -> Unit = {},
) {
    var source by rememberSaveable(gogEnabled) {
        mutableStateOf(if (gogEnabled) ExplorerSource.GOG else ExplorerSource.STEAM)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val steamState by steamViewModel.state.collectAsStateWithLifecycle()
    val featured by RecommendationRepository.featuredList.collectAsStateWithLifecycle()

    LaunchedEffect(source, gogEnabled) {
        if (source == ExplorerSource.GOG && gogEnabled) {
            viewModel.loadIfNeeded()
            PrefManager.recommendedTabSeenDay = System.currentTimeMillis() / (24L * 60 * 60 * 1000)
            if (PrefManager.usageAnalyticsEnabled) {
                PostHog.capture(
                    event = "recommendation_tab_opened",
                    properties = mapOf("\$set" to mapOf("recommendation_enabled" to true)),
                )
            }
        }
    }

    val items = remember(state.cards, featured) {
        val campaigns = featured.mapIndexed { index, item -> item.toLibraryItem(index) }
        campaigns + state.cards.mapIndexed { index, card -> card.toLibraryItem(campaigns.size + index) }
    }

    LaunchedEffect(source, items.size, steamState.results.size) {
        onItemCountChanged(
            if (source == ExplorerSource.STEAM) steamState.results.size else items.size,
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 72.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = source == ExplorerSource.GOG,
                onClick = {
                    if (gogEnabled) {
                        source = ExplorerSource.GOG
                    } else {
                        onRequestGogConsent()
                    }
                },
                label = { Text(stringResource(R.string.explorer_source_gog)) },
            )
            FilterChip(
                selected = source == ExplorerSource.STEAM,
                onClick = { source = ExplorerSource.STEAM },
                label = { Text(stringResource(R.string.explorer_source_steam)) },
            )
        }

        when (source) {
            ExplorerSource.GOG -> GogExplorerPane(
                stateLoading = state.loading,
                items = items,
                currentPaneType = currentPaneType,
                onNavigate = onNavigate,
                onRefresh = viewModel::refresh,
                modifier = Modifier.weight(1f),
                firstCarouselItemFocusRequester = firstCarouselItemFocusRequester,
                firstGridItemFocusRequester = firstGridItemFocusRequester,
                focusTargetListIndex = focusTargetListIndex,
                onFocusedIndexChanged = onFocusedIndexChanged,
                compatibilityMap = state.compatibilityMap,
                deviceGameStats = state.deviceGameStats,
                gpuGameStats = state.gpuGameStats,
                cards = state.cards,
                featured = featured,
            )

            ExplorerSource.STEAM -> SteamExplorerPane(
                state = steamState,
                steamAvailable = steamAvailable,
                viewModel = steamViewModel,
                onNavigate = onNavigate,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun GogExplorerPane(
    stateLoading: Boolean,
    items: List<LibraryItem>,
    currentPaneType: PaneType,
    onNavigate: (LibraryItem) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier,
    firstCarouselItemFocusRequester: FocusRequester?,
    firstGridItemFocusRequester: FocusRequester?,
    focusTargetListIndex: Int,
    onFocusedIndexChanged: (Int) -> Unit,
    compatibilityMap: Map<String, app.gamenative.data.GameCompatibilityStatus>,
    deviceGameStats: Map<GameSource, Map<String, app.gamenative.utils.DeviceGameStatsService.DeviceGameStats>>,
    gpuGameStats: Map<GameSource, Map<String, app.gamenative.utils.DeviceGameStatsService.DeviceGameStats>>,
    cards: List<GogRecCard>,
    featured: List<FeaturedItem>,
) {
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val seenIndices = remember { mutableSetOf<Int>() }
    val currentCards by rememberUpdatedState(cards)

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.map { it.index } }
            .collect { seenIndices.addAll(it) }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .collect { seenIndices.addAll(it) }
    }
    DisposableEffect(Unit) {
        onDispose {
            val seenRanks = seenIndices.map { it - featured.size }.filter { it >= 0 }.sorted()
            if (PrefManager.usageAnalyticsEnabled && seenRanks.isNotEmpty()) {
                val gameIds = seenRanks.mapNotNull { currentCards.getOrNull(it)?.productId }
                PostHog.capture(
                    event = "recommendation_tab_viewed",
                    properties = mapOf(
                        "impressed_count" to seenRanks.size,
                        "max_rank" to (seenRanks.lastOrNull() ?: -1),
                        "game_ids" to gameIds,
                    ),
                )
            }
        }
    }

    val currentItems by rememberUpdatedState(items)
    val currentFeatured by rememberUpdatedState(featured)
    val currentLayout by rememberUpdatedState(currentPaneType)
    val impressions = remember { RecImpressionTracker() }
    LaunchedEffect(gridState, listState) {
        snapshotFlow {
            val grid = gridState.layoutInfo.visibleItemsInfo.map { it.index }
            val list = listState.layoutInfo.visibleItemsInfo.map { it.index }
            (grid + list).toSet()
        }.collect { impressions.update(it, currentItems, currentFeatured, currentLayout) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            impressions.tick(currentItems, currentFeatured, currentLayout)
        }
    }
    DisposableEffect(Unit) {
        onDispose { impressions.tick(currentItems, currentFeatured, currentLayout) }
    }

    val recState = remember(items, compatibilityMap, deviceGameStats, gpuGameStats) {
        LibraryState(
            appInfoList = items,
            totalAppsInFilter = items.size,
            appInfoSortType = EnumSet.of(AppFilter.GAME),
            compatibilityMap = compatibilityMap,
            deviceGameStats = deviceGameStats,
            gpuGameStats = gpuGameStats,
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            stateLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            items.isEmpty() -> Text(
                text = stringResource(R.string.gog_rec_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            )
            currentPaneType == PaneType.CAROUSEL -> LibraryCarouselPane(
                state = recState,
                listState = listState,
                onPageChange = {},
                onNavigate = { appId -> items.find { it.appId == appId }?.let(onNavigate) },
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
                firstCarouselItemFocusRequester = firstCarouselItemFocusRequester,
                focusTargetListIndex = focusTargetListIndex,
                onFocusedIndexChanged = onFocusedIndexChanged,
            )
            else -> LibraryListPane(
                state = recState,
                listState = gridState,
                currentLayout = currentPaneType,
                onPageChange = {},
                onNavigate = { appId -> items.find { it.appId == appId }?.let(onNavigate) },
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
                firstGridItemFocusRequester = firstGridItemFocusRequester,
                focusTargetListIndex = focusTargetListIndex,
            )
        }
    }
}

@Composable
private fun SteamExplorerPane(
    state: SteamExplorerViewModel.UiState,
    steamAvailable: Boolean,
    viewModel: SteamExplorerViewModel,
    onNavigate: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showFilters by rememberSaveable { mutableStateOf(false) }

    fun openStore(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    if (showFilters) {
        SteamExplorerFilterDialog(
            state = state,
            onDismiss = { showFilters = false },
            onToggleOption = viewModel::toggleOption,
            onCycleTag = viewModel::cycleTag,
            onSetSort = viewModel::setSort,
            onSetMaxPrice = viewModel::setMaxPrice,
            onSetSpecialsOnly = viewModel::setSpecialsOnly,
            onSetHideFreeToPlay = viewModel::setHideFreeToPlay,
            onClearFilters = viewModel::clearFilters,
            onClearCache = viewModel::clearCache,
        )
    }

    state.selectedResult?.let { selected ->
        SteamExplorerDetailsDialog(
            result = selected,
            details = state.details,
            loading = state.detailsLoading,
            fromCache = state.detailsFromCache,
            staleCache = state.detailsStaleCache,
            error = state.detailsError,
            steamAvailable = steamAvailable,
            openingAppId = state.openingAppId,
            onDismiss = viewModel::closeDetails,
            onRefresh = viewModel::refreshSelectedDetails,
            onOpenStore = { openStore(selected.storeUrl) },
            onOpenLuaTools = if (selected.appId != null) {
                {
                    viewModel.closeDetails()
                    viewModel.openApp(selected, onNavigate)
                }
            } else {
                null
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                singleLine = true,
                label = { Text(stringResource(R.string.explorer_steam_search_hint)) },
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { showFilters = true }) {
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    if (state.filters.activeCount > 0) {
                        stringResource(R.string.explorer_steam_filters_count, state.filters.activeCount)
                    } else {
                        stringResource(R.string.explorer_steam_filters)
                    },
                )
            }
            IconButton(
                enabled = !state.searchLoading,
                onClick = viewModel::refresh,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.searchLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Text(
                text = stringResource(R.string.explorer_steam_results_count, state.totalResults),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.searchFromCache) {
                Text(
                    text = if (state.staleCache) {
                        stringResource(R.string.explorer_steam_cache_stale)
                    } else {
                        stringResource(R.string.explorer_steam_cache)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        state.error?.let { error ->
            Text(
                text = stringResource(R.string.explorer_steam_search_error, error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        if (!state.searchLoading && state.results.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.explorer_steam_no_results),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                gridItems(state.results, key = { it.key }) { result ->
                    val appId = result.appId
                    val primaryImage = remember(result.key, result.imageUrl) {
                        if (appId != null) {
                            "https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/library_600x900_2x.jpg"
                        } else {
                            result.imageUrl
                        }
                    }
                    var currentImage by remember(result.key, primaryImage, result.imageUrl) {
                        mutableStateOf(primaryImage.ifBlank { result.imageUrl })
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectResult(result) },
                    ) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(2f / 3f)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (currentImage.isNotBlank()) {
                                    SteamExplorerArtwork(
                                        imageUrl = currentImage,
                                        modifier = Modifier.fillMaxSize(),
                                        onFailure = {
                                            if (currentImage != result.imageUrl && result.imageUrl.isNotBlank()) {
                                                currentImage = result.imageUrl
                                            }
                                        },
                                    )
                                } else {
                                    Text(
                                        text = result.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.padding(12.dp),
                                    )
                                }

                                if (appId != null && SteamService.isAppInstalled(appId)) {
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(8.dp),
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.installed),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }

                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = result.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = buildString {
                                        append(
                                            when (result.kind) {
                                                SteamStoreItemKind.APP -> "App"
                                                SteamStoreItemKind.BUNDLE -> "Bundle"
                                                SteamStoreItemKind.PACKAGE -> "Package"
                                                SteamStoreItemKind.OTHER -> "Store"
                                            },
                                        )
                                        if (result.priceText.isNotBlank()) {
                                            append(" · ").append(result.priceText)
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SteamExplorerDetailsDialog(
    result: app.gamenative.steam.SteamStoreSearchResult,
    details: SteamStoreAppDetails?,
    loading: Boolean,
    fromCache: Boolean,
    staleCache: Boolean,
    error: String?,
    steamAvailable: Boolean,
    openingAppId: Int?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenLuaTools: (() -> Unit)?,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = details?.name?.ifBlank { result.name } ?: result.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onRefresh, enabled = result.appId != null && !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                }

                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item("hero") {
                        val hero = details?.headerImage.orEmpty().ifBlank { result.imageUrl }
                        if (hero.isNotBlank()) {
                            SteamExplorerArtwork(
                                imageUrl = hero,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(460f / 215f),
                            )
                        }
                    }

                    if (loading) {
                        item("loading") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }

                    if (details != null) {
                        item("summary") {
                            Column(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = details.type.ifBlank { "app" },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = stringResource(R.string.explorer_steam_appid, details.appId),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (fromCache) {
                                        Text(
                                            text = if (staleCache) {
                                                stringResource(R.string.explorer_steam_cache_stale)
                                            } else {
                                                stringResource(R.string.explorer_steam_cache)
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }

                                details.price?.let { price ->
                                    Text(
                                        text = buildString {
                                            if (price.discountPercent > 0) {
                                                append("-").append(price.discountPercent).append("% · ")
                                            }
                                            append(price.finalFormatted.ifBlank { price.initialFormatted })
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                } ?: if (details.isFree) {
                                    Text(
                                        text = stringResource(R.string.explorer_steam_free),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }

                                if (details.shortDescription.isNotBlank()) {
                                    Text(
                                        text = details.shortDescription,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }

                        item("metadata") {
                            SteamDetailsSection(
                                title = stringResource(R.string.explorer_steam_details_information),
                            ) {
                                SteamDetailsValue(
                                    stringResource(R.string.explorer_steam_details_release),
                                    details.releaseDate.ifBlank {
                                        if (details.comingSoon) stringResource(R.string.explorer_steam_coming_soon) else "—"
                                    },
                                )
                                SteamDetailsValue(
                                    stringResource(R.string.explorer_steam_details_developer),
                                    details.developers.joinToString().ifBlank { "—" },
                                )
                                SteamDetailsValue(
                                    stringResource(R.string.explorer_steam_details_publisher),
                                    details.publishers.joinToString().ifBlank { "—" },
                                )
                                SteamDetailsValue(
                                    stringResource(R.string.explorer_steam_details_platforms),
                                    buildList {
                                        if (details.platforms.windows) add("Windows")
                                        if (details.platforms.mac) add("macOS")
                                        if (details.platforms.linux) add("Linux")
                                    }.joinToString().ifBlank { "—" },
                                )
                                SteamDetailsValue(
                                    stringResource(R.string.explorer_steam_details_genres),
                                    details.genres.joinToString { it.description }.ifBlank { "—" },
                                )
                                SteamDetailsValue(
                                    stringResource(R.string.explorer_steam_details_categories),
                                    details.categories.joinToString { it.description }.ifBlank { "—" },
                                )
                                details.metacriticScore?.let {
                                    SteamDetailsValue("Metacritic", it.toString())
                                }
                                details.recommendationsTotal?.let {
                                    SteamDetailsValue(
                                        stringResource(R.string.explorer_steam_details_recommendations),
                                        it.toString(),
                                    )
                                }
                                details.achievementsTotal?.let {
                                    SteamDetailsValue(
                                        stringResource(R.string.explorer_steam_details_achievements),
                                        it.toString(),
                                    )
                                }
                            }
                        }

                        if (details.aboutTheGame.isNotBlank() || details.detailedDescription.isNotBlank()) {
                            item("about") {
                                SteamDetailsSection(
                                    title = stringResource(R.string.explorer_steam_details_about),
                                ) {
                                    Text(
                                        text = details.aboutTheGame.ifBlank { details.detailedDescription },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }

                        if (details.supportedLanguages.isNotBlank()) {
                            item("languages") {
                                SteamDetailsSection(
                                    title = stringResource(R.string.explorer_steam_details_languages),
                                ) {
                                    Text(details.supportedLanguages, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }

                        if (
                            details.pcRequirementsMinimum.isNotBlank() ||
                            details.pcRequirementsRecommended.isNotBlank()
                        ) {
                            item("pc-requirements") {
                                SteamDetailsSection(
                                    title = stringResource(R.string.explorer_steam_details_pc_requirements),
                                ) {
                                    if (details.pcRequirementsMinimum.isNotBlank()) {
                                        SteamDetailsValue(
                                            stringResource(R.string.explorer_steam_details_minimum),
                                            details.pcRequirementsMinimum,
                                        )
                                    }
                                    if (details.pcRequirementsRecommended.isNotBlank()) {
                                        SteamDetailsValue(
                                            stringResource(R.string.explorer_steam_details_recommended),
                                            details.pcRequirementsRecommended,
                                        )
                                    }
                                }
                            }
                        }

                        details.screenshots.take(4).forEachIndexed { index, screenshot ->
                            item("screenshot-$index") {
                                val imageUrl = screenshot.fullUrl.ifBlank { screenshot.thumbnailUrl }
                                if (imageUrl.isNotBlank()) {
                                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                                        if (index == 0) {
                                            Text(
                                                text = stringResource(R.string.explorer_steam_details_screenshots),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(bottom = 8.dp),
                                            )
                                        }
                                        SteamExplorerArtwork(
                                            imageUrl = imageUrl,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(16f / 9f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    error?.let { message ->
                        item("error") {
                            Text(
                                text = stringResource(R.string.explorer_steam_details_error, message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                        }
                    }

                    item("actions") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = onOpenStore,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.explorer_steam_open_store))
                            }

                            if (onOpenLuaTools != null) {
                                OutlinedButton(
                                    onClick = onOpenLuaTools,
                                    enabled = steamAvailable && openingAppId == null,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    if (openingAppId != null) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(modifier = Modifier.size(8.dp))
                                    }
                                    Text(
                                        if (steamAvailable) {
                                            stringResource(R.string.explorer_steam_open_luatools)
                                        } else {
                                            stringResource(R.string.explorer_steam_login_for_luatools)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SteamExplorerArtwork(
    imageUrl: String,
    modifier: Modifier = Modifier,
    onFailure: () -> Unit = {},
) {
    CoilImage(
        modifier = modifier,
        imageModel = { imageUrl },
        imageOptions = ImageOptions(
            contentScale = ContentScale.Crop,
            contentDescription = null,
        ),
        loading = {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        },
        failure = {
            onFailure()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "?",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun SteamDetailsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun SteamDetailsValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SteamExplorerFilterDialog(
    state: SteamExplorerViewModel.UiState,
    onDismiss: () -> Unit,
    onToggleOption: (SteamStoreFilterOption) -> Unit,
    onCycleTag: (String) -> Unit,
    onSetSort: (SteamStoreSort) -> Unit,
    onSetMaxPrice: (String?) -> Unit,
    onSetSpecialsOnly: (Boolean) -> Unit,
    onSetHideFreeToPlay: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
    onClearCache: () -> Unit,
) {
    var optionSearch by rememberSaveable { mutableStateOf("") }
    var expandedGroups by remember {
        mutableStateOf(setOf("category1", "tags"))
    }
    var priceDraft by remember(state.filters.maxPrice) {
        mutableStateOf(state.filters.maxPrice.orEmpty())
    }

    val visibleGroups = remember(state.filterCatalog.groups, optionSearch) {
        val query = optionSearch.trim()
        state.filterCatalog.groups.mapNotNull { group ->
            val options = if (query.isBlank()) {
                group.options
            } else {
                group.options.filter { it.label.contains(query, ignoreCase = true) }
            }
            group.takeIf { options.isNotEmpty() }?.copy(options = options)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.90f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.explorer_steam_filters_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(
                                R.string.explorer_steam_filters_active,
                                state.filters.activeCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row {
                        TextButton(onClick = onClearFilters, enabled = state.filters.activeCount > 0) {
                            Text(stringResource(R.string.explorer_steam_filters_clear))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.close))
                        }
                    }
                }

                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    item("sort-title") {
                        SteamFilterSectionTitle(stringResource(R.string.explorer_steam_sort))
                    }
                    items(SteamStoreSort.entries, key = { "sort:${it.name}" }) { sort ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSetSort(sort) }
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.filters.sort == sort,
                                onClick = { onSetSort(sort) },
                            )
                            Text(sort.displayName)
                        }
                    }

                    item("price") {
                        SteamFilterSectionTitle(stringResource(R.string.explorer_steam_price_discount))
                        SteamBooleanFilterRow(
                            label = stringResource(R.string.explorer_steam_free_only),
                            checked = state.filters.maxPrice == "free",
                            onCheckedChange = { enabled ->
                                priceDraft = ""
                                onSetMaxPrice(if (enabled) "free" else null)
                            },
                        )
                        OutlinedTextField(
                            value = if (state.filters.maxPrice == "free") "" else priceDraft,
                            onValueChange = { value ->
                                priceDraft = value.filter { it.isDigit() || it == '.' || it == ',' }
                                onSetMaxPrice(priceDraft.replace(',', '.'))
                            },
                            enabled = state.filters.maxPrice != "free",
                            singleLine = true,
                            label = { Text(stringResource(R.string.explorer_steam_max_price)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                        SteamBooleanFilterRow(
                            label = stringResource(R.string.explorer_steam_specials_only),
                            checked = state.filters.specialsOnly,
                            onCheckedChange = onSetSpecialsOnly,
                        )
                        SteamBooleanFilterRow(
                            label = stringResource(R.string.explorer_steam_hide_f2p),
                            checked = state.filters.hideFreeToPlay,
                            onCheckedChange = onSetHideFreeToPlay,
                        )
                    }

                    item("option-search") {
                        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                        OutlinedTextField(
                            value = optionSearch,
                            onValueChange = { optionSearch = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.explorer_steam_filter_search)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                        )
                        if (state.filterCatalogLoading) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.explorer_steam_loading_filters))
                            }
                        }
                        state.filterError?.let {
                            Text(
                                text = stringResource(R.string.explorer_steam_filter_fallback),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            )
                        }
                    }

                    visibleGroups.forEach { group ->
                        item(key = "group:${group.key}") {
                            val expanded = optionSearch.isNotBlank() || group.key in expandedGroups
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expandedGroups = if (expanded) {
                                            expandedGroups - group.key
                                        } else {
                                            expandedGroups + group.key
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = group.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = group.options.size.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        val expanded = optionSearch.isNotBlank() || group.key in expandedGroups
                        if (expanded) {
                            items(
                                items = group.options,
                                key = { option -> "option:${option.param}:${option.value}" },
                            ) { option ->
                                if (option.param == "tags") {
                                    val included = option.value in state.filters.selected["tags"].orEmpty()
                                    val excluded = option.value in state.filters.excludedTagIds
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onCycleTag(option.value) }
                                            .padding(horizontal = 24.dp, vertical = 9.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = option.label,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = when {
                                                included -> stringResource(R.string.explorer_steam_tag_include)
                                                excluded -> stringResource(R.string.explorer_steam_tag_exclude)
                                                else -> stringResource(R.string.explorer_steam_tag_off)
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = when {
                                                included -> MaterialTheme.colorScheme.primary
                                                excluded -> MaterialTheme.colorScheme.error
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                } else {
                                    val checked = option.value in state.filters.selected[option.param].orEmpty()
                                    SteamBooleanFilterRow(
                                        label = option.label,
                                        checked = checked,
                                        onCheckedChange = { onToggleOption(option) },
                                        horizontalPadding = 24.dp,
                                    )
                                }
                            }
                        }
                    }

                    item("cache") {
                        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                        SteamFilterSectionTitle(stringResource(R.string.explorer_steam_cache_title))
                        Text(
                            text = stringResource(R.string.explorer_steam_cache_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        TextButton(
                            onClick = onClearCache,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Text(stringResource(R.string.explorer_steam_cache_clear))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SteamFilterSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun SteamBooleanFilterRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    horizontalPadding: androidx.compose.ui.unit.Dp = 20.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = horizontalPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun FeaturedItem.toLibraryItem(index: Int): LibraryItem = LibraryItem(
    index = index,
    appId = "FEATURED_$campaignId",
    name = title,
    heroImageUrl = heroImageUrl,
    headerImageUrl = heroImageUrl,
    capsuleImageUrl = capsuleImageUrl ?: heroImageUrl,
    iconHash = iconUrl ?: capsuleImageUrl ?: heroImageUrl,
    gameSource = GameSource.STEAM,
    isRecommended = true,
    isFeatured = true,
    recommendedGameId = campaignId,
    recSource = "tab",
)

private fun GogRecCard.toLibraryItem(index: Int): LibraryItem = LibraryItem(
    index = index,
    appId = "GOGREC_$productId",
    name = title,
    capsuleImageUrl = capsuleImage,
    headerImageUrl = heroImage,
    heroImageUrl = heroImage,
    gameSource = GameSource.GOG,
    isRecommended = true,
    recommendedGameId = productId.toString(),
    recRating = rating,
    recDiscount = discountLabel,
    recPrice = priceLabel,
    recBasePrice = basePriceLabel,
    recSeedCount = seedCount,
    recSeedIconUrl = seedIconUrl,
    recStoreCard = true,
    recSource = "tab",
)

/** Cards already reported this process, so re-entering the tab or a list reorder doesn't re-count them. */
private object RecImpressionSession {
    val seen = mutableSetOf<String>()
}

private class RecImpressionTracker {
    private val visibleSince = mutableMapOf<Int, Long>()
    private val impressed = mutableSetOf<Int>()

    fun update(visible: Set<Int>, items: List<LibraryItem>, featured: List<FeaturedItem>, layout: PaneType) {
        val now = SystemClock.elapsedRealtime()
        Timber.tag("RecImpression").d("visible=%d items=%d layout=%s", visible.size, items.size, layout)
        visibleSince.keys.filter { it !in visible }.forEach { index ->
            val since = visibleSince.remove(index) ?: return@forEach
            if (now - since >= MIN_VISIBLE_MS) emit(index, items, featured, layout)
        }
        visible.forEach { visibleSince.putIfAbsent(it, now) }
        tick(items, featured, layout)
    }

    fun tick(items: List<LibraryItem>, featured: List<FeaturedItem>, layout: PaneType) {
        val now = SystemClock.elapsedRealtime()
        visibleSince.forEach { (index, since) ->
            if (index !in impressed && now - since >= MIN_VISIBLE_MS) emit(index, items, featured, layout)
        }
    }

    private fun emit(index: Int, items: List<LibraryItem>, featured: List<FeaturedItem>, layout: PaneType) {
        if (!impressed.add(index)) return
        val item = items.getOrNull(index) ?: return
        val key = (if (item.isFeatured) "featured:" else "gog:") + item.recommendedGameId
        if (!RecImpressionSession.seen.add(key)) return
        Timber.tag("RecImpression").d("emit rank=%d %s", index, item.name)
        if (item.isFeatured) {
            val campaign = featured.getOrNull(index)
            ConversionTracker.track(
                "featured_impression",
                mapOf(
                    "campaign_id" to item.recommendedGameId,
                    "game_name" to item.name,
                    "rank" to index,
                    "source" to item.recSource,
                    "layout" to layout.name,
                    "status" to (campaign?.status ?: ""),
                    "cta_count" to (campaign?.actions?.size ?: 0),
                    "cta_types" to (campaign?.actions?.map { it.type.uppercase() } ?: emptyList()),
                ),
            )
        } else {
            ConversionTracker.track(
                "recommendation_impression",
                mapOf(
                    "game_id" to item.recommendedGameId,
                    "game_name" to item.name,
                    "rank" to index,
                    "source" to item.recSource,
                    "layout" to layout.name,
                    "seed_count" to item.recSeedCount,
                    "discount" to (item.recDiscount ?: ""),
                    "price" to (item.recPrice ?: ""),
                ),
            )
        }
    }

    private companion object {
        const val MIN_VISIBLE_MS = 1000L
    }
}
