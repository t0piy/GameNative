package app.gamenative.steam

data class SteamStorePriceOverview(
    val currency: String = "",
    val initialFormatted: String = "",
    val finalFormatted: String = "",
    val discountPercent: Int = 0,
)

data class SteamStorePlatformSupport(
    val windows: Boolean = false,
    val mac: Boolean = false,
    val linux: Boolean = false,
)

data class SteamStoreNamedValue(
    val id: Int = 0,
    val description: String,
)

data class SteamStoreScreenshot(
    val id: Int = 0,
    val thumbnailUrl: String = "",
    val fullUrl: String = "",
)

data class SteamStoreAppDetails(
    val appId: Int,
    val type: String = "",
    val name: String = "",
    val requiredAge: String = "",
    val isFree: Boolean = false,
    val shortDescription: String = "",
    val detailedDescription: String = "",
    val aboutTheGame: String = "",
    val supportedLanguages: String = "",
    val headerImage: String = "",
    val capsuleImage: String = "",
    val capsuleImageV5: String = "",
    val website: String = "",
    val developers: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val price: SteamStorePriceOverview? = null,
    val platforms: SteamStorePlatformSupport = SteamStorePlatformSupport(),
    val metacriticScore: Int? = null,
    val metacriticUrl: String = "",
    val categories: List<SteamStoreNamedValue> = emptyList(),
    val genres: List<SteamStoreNamedValue> = emptyList(),
    val recommendationsTotal: Int? = null,
    val achievementsTotal: Int? = null,
    val releaseDate: String = "",
    val comingSoon: Boolean = false,
    val supportUrl: String = "",
    val supportEmail: String = "",
    val pcRequirementsMinimum: String = "",
    val pcRequirementsRecommended: String = "",
    val macRequirementsMinimum: String = "",
    val macRequirementsRecommended: String = "",
    val linuxRequirementsMinimum: String = "",
    val linuxRequirementsRecommended: String = "",
    val screenshots: List<SteamStoreScreenshot> = emptyList(),
)
