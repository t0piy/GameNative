package app.gamenative.steam

import org.json.JSONObject

/**
 * Parser for Steam Store search HTML/JSON.
 *
 * Steam exposes its current filter controls as data-param/data-value/data-loc attributes. Reading
 * those attributes means new tags/features can appear in Explorer without a hardcoded app update.
 */
object SteamStoreSearchParser {
    data class SearchPage(
        val results: List<SteamStoreSearchResult>,
        val totalCount: Int,
    )


    private val PLAYER_FILTER_LABELS = setOf(
        "Single-player",
        "Multi-player",
        "PvP",
        "Online PvP",
        "LAN PvP",
        "Shared/Split Screen PvP",
        "Co-op",
        "Online Co-op",
        "LAN Co-op",
        "Shared/Split Screen Co-op",
        "Shared/Split Screen",
        "Cross-Platform Multiplayer",
    )

    private val ACCESSIBILITY_FILTER_LABELS = setOf(
        "Adjustable Difficulty",
        "Save Anytime",
        "Adjustable Text Size",
        "Subtitle Options",
        "Color Alternatives",
        "Camera Comfort",
        "Playable without Vision",
        "Contrast Controls",
        "Custom Volume Controls",
        "Stereo Sound",
        "Surround Sound",
        "Narrated Game Menus",
        "Playable without Timed Input",
        "Keyboard Only Option",
        "Mouse Only Option",
        "Touch Only Option",
        "Chat Speech-to-text",
        "Chat Text-to-speech",
        "Playable at Your Own Pace",
    )

    private val VR_FILTER_LABELS = setOf(
        "VR Only",
        "VR Supported",
    )

    private val controlTagRegex = Regex(
        """<(?:div|span)\b(?=[^>]*\bdata-param\s*=\s*["'][^"']+["'])(?=[^>]*\bdata-value\s*=\s*["'][^"']*["'])[^>]*>""",
        setOf(RegexOption.IGNORE_CASE),
    )
    private val attributeRegex = Regex("""([\w-]+)\s*=\s*["']([^"']*)["']""")
    private val rowRegex = Regex(
        """<a\b(?=[^>]*\bclass\s*=\s*["'][^"']*search_result_row[^"']*["'])[^>]*>.*?</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun parseFilterCatalog(html: String, fetchedAt: Long = System.currentTimeMillis()): SteamStoreFilterCatalog {
        val options = controlTagRegex.findAll(html)
            .mapNotNull { match ->
                val attrs = attributes(match.value)
                val param = attrs["data-param"].orEmpty().trim()
                val value = attrs["data-value"].orEmpty().trim()
                val label = decodeHtml(attrs["data-loc"].orEmpty()).trim()
                if (param.isBlank() || value.isBlank() || label.isBlank() || param == "untags") {
                    null
                } else {
                    SteamStoreFilterOption(
                        param = param,
                        value = value,
                        label = label,
                        group = groupTitle(param, label),
                    )
                }
            }
            .distinctBy { Triple(it.param, it.value, it.label) }
            .toList()

        val merged = if (options.isEmpty()) fallbackOptions() else {
            (options + fallbackOptions())
                .distinctBy { Pair(it.param, it.value) }
        }

        val groups = merged
            .groupBy { option -> option.param to option.group }
            .map { (identity, values) ->
                val (param, title) = identity
                val keySuffix = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                SteamStoreFilterGroup(
                    key = if (title == groupTitle(param)) param else "$param:$keySuffix",
                    title = title,
                    options = values.sortedBy { it.label.lowercase() },
                )
            }
            .sortedWith(
                compareBy<SteamStoreFilterGroup> { groupOrder(it.key, it.title) }
                    .thenBy { it.title.lowercase() },
            )

        return SteamStoreFilterCatalog(groups = groups, fetchedAt = fetchedAt)
    }

    fun parseSearchResponse(body: String): SearchPage {
        if (body.isBlank()) return SearchPage(emptyList(), 0)

        val trimmed = body.trimStart()
        if (trimmed.startsWith("{")) {
            runCatching {
                val json = JSONObject(body)
                val html = json.optString("results_html")
                if (html.isNotBlank()) {
                    return SearchPage(
                        results = parseSearchHtml(html),
                        totalCount = json.optInt("total_count", json.optInt("search_result_count", 0)),
                    )
                }

                val items = json.optJSONArray("items")
                if (items != null) {
                    val results = buildList {
                        for (index in 0 until items.length()) {
                            val item = items.optJSONObject(index) ?: continue
                            val id = item.optInt("id", 0)
                            val name = decodeHtml(item.optString("name")).trim()
                            if (id <= 0 || name.isBlank()) continue
                            val url = "https://store.steampowered.com/app/$id/"
                            add(
                                SteamStoreSearchResult(
                                    key = "APP:$id",
                                    kind = SteamStoreItemKind.APP,
                                    itemId = id,
                                    name = name,
                                    storeUrl = url,
                                    imageUrl = item.optString("logo"),
                                ),
                            )
                        }
                    }
                    return SearchPage(results, json.optInt("total", results.size))
                }
            }
        }

        val results = parseSearchHtml(body)
        return SearchPage(results, results.size)
    }

    fun parseSearchHtml(html: String): List<SteamStoreSearchResult> =
        rowRegex.findAll(html)
            .mapNotNull { match -> parseRow(match.value) }
            .distinctBy { it.key }
            .toList()

    private fun parseRow(row: String): SteamStoreSearchResult? {
        val openingTag = row.substringBefore('>') + ">"
        val href = decodeHtml(attributes(openingTag)["href"].orEmpty())
        if (href.isBlank()) return null

        val name = findClassText(row, "title")
        if (name.isBlank()) return null

        val (kind, itemId) = kindAndId(href)
        val key = if (itemId != null) {
            "${kind.name}:$itemId"
        } else {
            "OTHER:$href"
        }

        val imageUrl = Regex(
            """<img\b[^>]*(?:src|data-image-url)\s*=\s*["']([^"']+)["'][^>]*>""",
            RegexOption.IGNORE_CASE,
        ).find(row)?.groupValues?.getOrNull(1)?.let(::decodeHtml).orEmpty()

        return SteamStoreSearchResult(
            key = key,
            kind = kind,
            itemId = itemId,
            name = name,
            storeUrl = href,
            imageUrl = imageUrl,
            releaseDate = findClassText(row, "search_released"),
            priceText = findClassText(row, "discount_final_price")
                .ifBlank { findClassText(row, "search_price") },
        )
    }

    private fun findClassText(html: String, classFragment: String): String {
        val regex = Regex(
            """<(?:span|div)\b[^>]*class\s*=\s*["'][^"']*\b${Regex.escape(classFragment)}\b[^"']*["'][^>]*>(.*?)</(?:span|div)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val raw = regex.find(html)?.groupValues?.getOrNull(1).orEmpty()
        return decodeHtml(raw.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun kindAndId(url: String): Pair<SteamStoreItemKind, Int?> {
        Regex("""/app/(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return SteamStoreItemKind.APP to it
        }
        Regex("""/bundle/(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return SteamStoreItemKind.BUNDLE to it
        }
        Regex("""/sub/(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            return SteamStoreItemKind.PACKAGE to it
        }
        return SteamStoreItemKind.OTHER to null
    }

    private fun attributes(tag: String): Map<String, String> =
        attributeRegex.findAll(tag).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    private fun groupTitle(param: String, label: String = ""): String = when (param) {
        "tags" -> "Tags"
        "category1" -> "Product types"
        "category2", "category3" -> when {
            label in PLAYER_FILTER_LABELS -> "Number of players"
            label in ACCESSIBILITY_FILTER_LABELS -> "Accessibility"
            label in VR_FILTER_LABELS -> "VR support"
            else -> "Features"
        }
        "controllersupport" -> "Controller support"
        "deck_compatibility" -> "Steam Deck compatibility"
        "os" -> "Operating system"
        "supportedlang" -> "Language"
        else -> param
            .replace('_', ' ')
            .split(' ')
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }

    private fun groupOrder(key: String, title: String): Int = when {
        key == "category1" -> 0
        key == "tags" -> 1
        title == "Number of players" -> 2
        title == "Features" -> 3
        key.startsWith("controllersupport") -> 4
        title == "Accessibility" -> 5
        key.startsWith("deck_compatibility") -> 6
        title == "VR support" -> 7
        key.startsWith("os") -> 8
        key.startsWith("supportedlang") -> 9
        else -> 100
    }

    /**
     * Only a fallback. The normal path discovers the complete live list from Steam itself.
     */
    private fun fallbackOptions(): List<SteamStoreFilterOption> = buildList {
        fun option(param: String, value: String, label: String) {
            add(SteamStoreFilterOption(param, value, label, groupTitle(param, label)))
        }

        option("category1", "998", "Games")
        option("category1", "994", "Software")
        option("category1", "21", "Downloadable Content")
        option("category1", "10", "Demos")
        option("category1", "990", "Soundtracks")
        option("category1", "989", "Playtests")
        option("category1", "992", "Videos")
        option("category1", "997", "Mods")
        option("category1", "993", "Hardware")
        option("category1", "996", "Bundles")

        option("category2", "2", "Single-player")
        option("category2", "1", "Multi-player")
        option("category2", "49", "PvP")
        option("category2", "36", "Online PvP")
        option("category2", "47", "LAN PvP")
        option("category2", "37", "Shared/Split Screen PvP")
        option("category2", "9", "Co-op")
        option("category2", "38", "Online Co-op")
        option("category2", "48", "LAN Co-op")
        option("category2", "39", "Shared/Split Screen Co-op")
        option("category2", "24", "Shared/Split Screen")
        option("category2", "27", "Cross-Platform Multiplayer")
        option("category2", "22", "Steam Achievements")
        option("category2", "28", "Tracked Controller Support")
        option("category2", "29", "Steam Trading Cards")
        option("category2", "13", "Captions available")
        option("category2", "30", "Steam Workshop")
        option("category2", "40", "SteamVR Collectibles")
        option("category2", "23", "Steam Cloud")
        option("category2", "16", "Includes Source SDK")
        option("category2", "41", "Remote Play on Phone")
        option("category2", "42", "Remote Play on Tablet")
        option("category2", "43", "Remote Play on TV")
        option("category2", "44", "Remote Play Together")
        option("category2", "62", "Family Sharing")

        option("deck_compatibility", "3", "Verified")
        option("deck_compatibility", "2", "Playable")
        option("os", "win", "Windows")
        option("os", "mac", "macOS")
        option("os", "linux", "SteamOS + Linux")
    }

    private fun decodeHtml(value: String): String {
        if ('&' !in value) return value
        return value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("""&#(\d+);""")) { match ->
                match.groupValues[1].toIntOrNull()?.let { code ->
                    runCatching { code.toChar().toString() }.getOrNull()
                } ?: match.value
            }
            .replace(Regex("""&#x([0-9a-fA-F]+);""")) { match ->
                match.groupValues[1].toIntOrNull(16)?.let { code ->
                    runCatching { code.toChar().toString() }.getOrNull()
                } ?: match.value
            }
    }
}
