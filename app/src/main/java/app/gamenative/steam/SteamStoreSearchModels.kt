package app.gamenative.steam

import java.security.MessageDigest

enum class SteamStoreItemKind {
    APP,
    BUNDLE,
    PACKAGE,
    OTHER,
}

data class SteamStoreSearchResult(
    val key: String,
    val kind: SteamStoreItemKind,
    val itemId: Int?,
    val name: String,
    val storeUrl: String,
    val imageUrl: String = "",
    val releaseDate: String = "",
    val priceText: String = "",
) {
    val appId: Int?
        get() = itemId?.takeIf { kind == SteamStoreItemKind.APP }
}

data class SteamStoreFilterOption(
    val param: String,
    val value: String,
    val label: String,
    val group: String,
)

data class SteamStoreFilterGroup(
    val key: String,
    val title: String,
    val options: List<SteamStoreFilterOption>,
)

data class SteamStoreFilterCatalog(
    val groups: List<SteamStoreFilterGroup> = emptyList(),
    val fetchedAt: Long = 0L,
) {
    val tags: List<SteamStoreFilterOption>
        get() = groups.firstOrNull { it.key == "tags" }?.options.orEmpty()
}

enum class SteamStoreSort(val parameter: String, val displayName: String) {
    RELEVANCE("_ASC", "Relevance"),
    RELEASE_DATE("Released_DESC", "Release date"),
    NAME("Name_ASC", "Name"),
    PRICE_LOW("Price_ASC", "Lowest price"),
    PRICE_HIGH("Price_DESC", "Highest price"),
    USER_REVIEWS("Reviews_DESC", "User reviews"),
    DECK_REVIEW_DATE("DeckCompatDate_DESC", "Steam Deck review date"),
}

data class SteamStoreSearchFilters(
    /** data-param -> selected data-value entries scraped directly from Steam Store. */
    val selected: Map<String, Set<String>> = emptyMap(),
    val excludedTagIds: Set<String> = emptySet(),
    val sort: SteamStoreSort = SteamStoreSort.RELEVANCE,
    val maxPrice: String? = null,
    val specialsOnly: Boolean = false,
    val hideFreeToPlay: Boolean = false,
) {
    val hasAny: Boolean
        get() = selected.values.any { it.isNotEmpty() } ||
            excludedTagIds.isNotEmpty() ||
            !maxPrice.isNullOrBlank() ||
            specialsOnly ||
            hideFreeToPlay ||
            sort != SteamStoreSort.RELEVANCE

    val activeCount: Int
        get() = selected.values.sumOf { it.size } +
            excludedTagIds.size +
            listOf(!maxPrice.isNullOrBlank(), specialsOnly, hideFreeToPlay).count { it } +
            if (sort != SteamStoreSort.RELEVANCE) 1 else 0

    fun canonicalKey(query: String): String {
        val selectedKey = selected
            .filterValues { it.isNotEmpty() }
            .toSortedMap()
            .entries
            .joinToString("&") { (param, values) ->
                "$param=${values.sorted().joinToString(",")}"
            }
        val raw = buildString {
            append("q=").append(query.trim().lowercase())
            append("|sort=").append(sort.parameter)
            append("|max=").append(maxPrice.orEmpty())
            append("|specials=").append(specialsOnly)
            append("|hidef2p=").append(hideFreeToPlay)
            append("|untags=").append(excludedTagIds.sorted().joinToString(","))
            append("|selected=").append(selectedKey)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun toggle(option: SteamStoreFilterOption): SteamStoreSearchFilters {
        val current = selected[option.param].orEmpty()
        val nextValues = if (option.value in current) current - option.value else current + option.value
        val next = selected.toMutableMap()
        if (nextValues.isEmpty()) next.remove(option.param) else next[option.param] = nextValues
        return copy(selected = next)
    }

    fun cycleTag(tagId: String): SteamStoreSearchFilters {
        val included = selected["tags"].orEmpty()
        return when {
            tagId in included -> {
                val next = selected.toMutableMap()
                val newIncluded = included - tagId
                if (newIncluded.isEmpty()) next.remove("tags") else next["tags"] = newIncluded
                copy(selected = next, excludedTagIds = excludedTagIds + tagId)
            }
            tagId in excludedTagIds -> copy(excludedTagIds = excludedTagIds - tagId)
            else -> {
                val next = selected.toMutableMap()
                next["tags"] = included + tagId
                copy(selected = next)
            }
        }
    }
}
