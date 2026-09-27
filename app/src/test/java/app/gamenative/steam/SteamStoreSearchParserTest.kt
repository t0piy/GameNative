package app.gamenative.steam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamStoreSearchParserTest {
    @Test
    fun parseFilterCatalog_discoversLiveDataParamControls() {
        val html = """
            <div class="tab_filter_control_row" data-param="tags" data-value="19" data-loc="Action">
                <span class="tab_filter_control" data-param="tags" data-value="19" data-loc="Action"></span>
            </div>
            <div class="tab_filter_control_row" data-param="category1" data-value="994" data-loc="Software"></div>
            <div class="tab_filter_control_row" data-param="controllersupport" data-value="2" data-loc="Full Controller Support"></div>
            <div class="tab_filter_control_row" data-param="future_filter" data-value="777" data-loc="Future Steam Feature"></div>
        """.trimIndent()

        val catalog = SteamStoreSearchParser.parseFilterCatalog(html, fetchedAt = 123L)

        assertEquals(123L, catalog.fetchedAt)
        assertTrue(catalog.groups.flatMap { it.options }.any {
            it.param == "tags" && it.value == "19" && it.label == "Action"
        })
        assertTrue(catalog.groups.flatMap { it.options }.any {
            it.param == "category1" && it.value == "994" && it.label == "Software"
        })
        assertTrue(catalog.groups.flatMap { it.options }.any {
            it.param == "future_filter" && it.value == "777"
        })
    }

    @Test
    fun parseSearchResponse_keepsAppsBundlesAndPackages() {
        val html = """
            <a href="https://store.steampowered.com/app/123/Test_App/" class="search_result_row">
                <span class="title">Test &amp; App</span>
                <div class="col search_released responsive_secondrow">1 Jan, 2026</div>
                <div class="discount_final_price">R$ 10,00</div>
            </a>
            <a class="search_result_row" href="https://store.steampowered.com/bundle/456/Test_Bundle/">
                <span class="title">Test Bundle</span>
            </a>
            <a class="search_result_row" href="https://store.steampowered.com/sub/789/Test_Package/">
                <span class="title">Test Package</span>
            </a>
        """.trimIndent()
        val body = org.json.JSONObject()
            .put("total_count", 3)
            .put("results_html", html)
            .toString()

        val page = SteamStoreSearchParser.parseSearchResponse(body)

        assertEquals(3, page.totalCount)
        assertEquals(3, page.results.size)
        assertEquals(SteamStoreItemKind.APP, page.results[0].kind)
        assertEquals(123, page.results[0].itemId)
        assertEquals("Test & App", page.results[0].name)
        assertEquals(SteamStoreItemKind.BUNDLE, page.results[1].kind)
        assertEquals(456, page.results[1].itemId)
        assertEquals(SteamStoreItemKind.PACKAGE, page.results[2].kind)
        assertEquals(789, page.results[2].itemId)
    }

    @Test
    fun compactItems_inferAppBundleAndPackageIdsFromAssetUrls() {
        val body = org.json.JSONObject()
            .put(
                "items",
                org.json.JSONArray()
                    .put(
                        org.json.JSONObject()
                            .put("name", "Compact App")
                            .put("logo", "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/111/header.jpg"),
                    )
                    .put(
                        org.json.JSONObject()
                            .put("name", "Compact Bundle")
                            .put("logo", "https://shared.fastly.steamstatic.com/store_item_assets/steam/bundles/222/header.jpg"),
                    )
                    .put(
                        org.json.JSONObject()
                            .put("name", "Compact Package")
                            .put("logo", "https://shared.fastly.steamstatic.com/store_item_assets/steam/subs/333/header.jpg"),
                    ),
            )
            .put("total_count", 3)
            .toString()

        val page = SteamStoreSearchParser.parseSearchResponse(body)

        assertEquals(3, page.totalCount)
        assertEquals(
            listOf(
                SteamStoreItemKind.APP to 111,
                SteamStoreItemKind.BUNDLE to 222,
                SteamStoreItemKind.PACKAGE to 333,
            ),
            page.results.map { it.kind to it.itemId },
        )
        assertEquals("https://store.steampowered.com/bundle/222/", page.results[1].storeUrl)
        assertEquals("https://store.steampowered.com/sub/333/", page.results[2].storeUrl)
    }

    @Test
    fun filterKey_isStableAndTagCycleSupportsIncludeExclude() {
        val action = SteamStoreFilterOption("tags", "19", "Action", "Tags")
        val software = SteamStoreFilterOption("category1", "994", "Software", "Product types")

        val first = SteamStoreSearchFilters()
            .toggle(software)
            .cycleTag(action.value)
        val sameDifferentOrder = SteamStoreSearchFilters(
            selected = mapOf(
                "tags" to setOf("19"),
                "category1" to setOf("994"),
            ),
        )

        assertEquals(first.canonicalKey("test"), sameDifferentOrder.canonicalKey("test"))

        val excluded = first.cycleTag("19")
        assertTrue("19" !in excluded.selected["tags"].orEmpty())
        assertTrue("19" in excluded.excludedTagIds)

        val off = excluded.cycleTag("19")
        assertTrue("19" !in off.excludedTagIds)
        assertTrue("19" !in off.selected["tags"].orEmpty())
    }

    @Test
    fun emptyFilterHtml_hasProductTypeFallbackIncludingNonGames() {
        val catalog = SteamStoreSearchParser.parseFilterCatalog("")
        val productTypes = catalog.groups
            .first { it.key == "category1" }
            .options
            .associate { it.value to it.label }

        assertEquals("Games", productTypes["998"])
        assertEquals("Software", productTypes["994"])
        assertEquals("Downloadable Content", productTypes["21"])
        assertEquals("Demos", productTypes["10"])
        assertEquals("Soundtracks", productTypes["990"])
        assertEquals("Playtests", productTypes["989"])
        assertEquals("Videos", productTypes["992"])
        assertEquals("Mods", productTypes["997"])
        assertEquals("Hardware", productTypes["993"])
    }
}
