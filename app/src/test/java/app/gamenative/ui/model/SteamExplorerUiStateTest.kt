package app.gamenative.ui.model

import app.gamenative.steam.SteamStoreItemKind
import app.gamenative.steam.SteamStoreSearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SteamExplorerUiStateTest {
    private fun app(id: Int, name: String) = SteamStoreSearchResult(
        key = "APP:$id",
        kind = SteamStoreItemKind.APP,
        itemId = id,
        name = name,
        storeUrl = "https://store.steampowered.com/app/$id/",
    )

    @Test
    fun displayedResults_pinsManualEntriesAndDeduplicatesStoreResults() {
        val manual = app(1250650, "AI Shoujo")
        val other = app(570, "Dota 2")
        val state = SteamExplorerViewModel.UiState(
            manualResults = listOf(manual),
            manualAppIds = setOf(1250650),
            results = listOf(other, manual),
        )

        assertEquals(listOf(manual, other), state.displayedResults)
    }

    @Test
    fun displayedResults_filtersManualEntriesByNameOrExactAppId() {
        val manual = app(1250650, "AI Shoujo")
        val stateByName = SteamExplorerViewModel.UiState(
            query = "shoujo",
            manualResults = listOf(manual),
        )
        val stateById = SteamExplorerViewModel.UiState(
            query = "1250650",
            manualResults = listOf(manual),
        )
        val unrelated = SteamExplorerViewModel.UiState(
            query = "portal",
            manualResults = listOf(manual),
        )

        assertEquals(listOf(manual), stateByName.displayedResults)
        assertEquals(listOf(manual), stateById.displayedResults)
        assertEquals(emptyList<SteamStoreSearchResult>(), unrelated.displayedResults)
    }
}
