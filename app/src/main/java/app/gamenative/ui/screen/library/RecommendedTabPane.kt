package app.gamenative.ui.screen.library

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.FeaturedItem
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.RecommendationRepository
import app.gamenative.data.SteamCatalogEntry
import app.gamenative.data.gog.GogRecCard
import app.gamenative.service.SteamService
import app.gamenative.ui.data.LibraryState
import app.gamenative.ui.enums.AppFilter
import app.gamenative.ui.enums.PaneType
import app.gamenative.ui.model.GogRecommendationsViewModel
import app.gamenative.ui.model.SteamExplorerViewModel
import app.gamenative.ui.screen.library.components.LibraryCarouselPane
import app.gamenative.ui.screen.library.components.LibraryListPane
import app.gamenative.utils.ConversionTracker
import com.posthog.PostHog
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

    LaunchedEffect(source, steamAvailable, steamState.catalogSize) {
        if (source == ExplorerSource.STEAM && steamAvailable && steamState.catalogSize == 0 &&
            !steamState.syncState.isSyncing
        ) {
            steamViewModel.refresh()
        }
    }

    val items = remember(state.cards, featured) {
        val campaigns = featured.mapIndexed { index, item -> item.toLibraryItem(index) }
        campaigns + state.cards.mapIndexed { index, card -> card.toLibraryItem(campaigns.size + index) }
    }

    LaunchedEffect(source, items.size, steamState.games.size) {
        onItemCountChanged(
            if (source == ExplorerSource.STEAM) steamState.games.size else items.size,
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
                onQueryChange = steamViewModel::onQueryChange,
                onRefresh = { steamViewModel.refresh(forceFull = false) },
                onOpen = { entry -> steamViewModel.open(entry, onNavigate) },
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
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (SteamCatalogEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                onValueChange = onQueryChange,
                enabled = steamAvailable,
                singleLine = true,
                label = { Text(stringResource(R.string.explorer_steam_search_hint)) },
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = steamAvailable && !state.syncState.isSyncing,
                onClick = onRefresh,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        }

        val statusText = when {
            !steamAvailable -> stringResource(R.string.explorer_steam_login_required)
            state.syncState.isSyncing && state.syncState.totalChanges > 0 ->
                stringResource(
                    R.string.explorer_steam_indexing_progress,
                    state.syncState.processedChanges,
                    state.syncState.totalChanges,
                    state.syncState.indexedGames,
                )
            state.syncState.isSyncing -> stringResource(R.string.explorer_steam_indexing)
            state.catalogSize == 0 -> stringResource(R.string.explorer_steam_empty)
            else -> stringResource(R.string.explorer_steam_indexed_count, state.catalogSize)
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        state.syncState.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        if (state.syncState.isSyncing && state.catalogSize == 0) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.games, key = { it.appId }) { game ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = state.openingAppId == null) { onOpen(game) },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = game.name,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(R.string.explorer_steam_appid, game.appId),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.openingAppId == game.appId) {
                            CircularProgressIndicator()
                        } else if (SteamService.isAppInstalled(game.appId)) {
                            Text(
                                text = stringResource(R.string.installed),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
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
