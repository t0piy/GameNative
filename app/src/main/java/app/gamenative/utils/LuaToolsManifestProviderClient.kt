package app.gamenative.utils

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

enum class LuaToolsProviderTransport {
    Direct,
    LuaToolsProxy,
    Hubcap,
}

data class LuaToolsManifestSource(
    val name: String,
    val displayName: String = name,
    val status: String,
    val requiresUserKey: Boolean = false,
    val transport: LuaToolsProviderTransport = LuaToolsProviderTransport.LuaToolsProxy,
) {
    val available: Boolean
        get() = status.equals("available", ignoreCase = true)

    val canAttemptDownload: Boolean
        get() = transport == LuaToolsProviderTransport.Direct || available
}

/**
 * Android port of the manifest-provider plumbing used by LuaTools.
 *
 * Provider discovery mirrors LuaToolsApiClient.CheckSourcesAsync(): it talks to the public
 * manifest backend with the same fixed User-Agent. Standard provider downloads mirror
 * /api/manifest/download and therefore require a lua.tools bearer token; Hubcap/Sadie stays
 * direct and uses the user's Hubcap key.
 *
 * Whatever provider returns is handed to [SteamManifestOverrideStore], which only consumes
 * setManifestid pins and validated raw Steam manifests. Depot entitlement remains with Steam.
 */
object LuaToolsManifestProviderClient {
    const val HUBCAP_SOURCE_NAME = "Sadie (Morrenus)"

    private const val LUA_TOOLS_API_BASE = "https://lua.tools"
    private const val MANIFEST_BACKEND_BASE = "http://167.235.229.108"
    private const val MANIFEST_BACKEND_USER_AGENT = "secretgoonpoon"
    private const val HUBCAP_BASE = "https://hubcapmanifest.com"
    private const val MAX_PROVIDER_BYTES = 128L * 1024L * 1024L

    // These match HttpServerService.LoadApiSources() in the referenced LuaTools revision.
    // They are provider endpoints themselves, not lua.tools' authenticated proxy.
    private const val RYUU_DIRECT_TEMPLATE = "http://167.235.229.108/<appid>"
    private const val SUSHI_DIRECT_TEMPLATE =
        "https://raw.githubusercontent.com/sushi-dev55-alt/sushitools-games-repo-alt/refs/heads/main/<appid>.zip"

    private val sourceDisplayNames = mapOf(
        HUBCAP_SOURCE_NAME to "Sadie (Hubcap)",
    )

    private val keyRequiredSources = setOf(HUBCAP_SOURCE_NAME)

    private val directProviderTemplates = mapOf(
        "Ryuu" to RYUU_DIRECT_TEMPLATE,
        "Sushi" to SUSHI_DIRECT_TEMPLATE,
    )

    suspend fun checkSources(
        context: Context,
        appId: Int,
    ): List<LuaToolsManifestSource> =
        checkSources(
            appId = appId,
            hubcapApiKey = ManifestProviderAuthManager.getHubcapApiKey(context),
        )

    suspend fun checkSources(
        appId: Int,
        hubcapApiKey: String? = null,
    ): List<LuaToolsManifestSource> = withContext(Dispatchers.IO) {
        require(appId > 0) { "Invalid Steam app id" }

        val statuses = linkedMapOf<String, String>()
        runCatching {
            val request = Request.Builder()
                .url("$MANIFEST_BACKEND_BASE/check_apis?appid=$appId")
                .header("User-Agent", MANIFEST_BACKEND_USER_AGENT)
                .get()
                .build()

            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@use

                val json = JSONObject(body)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    statuses[name] = json.optString(name, "unknown")
                }
            }
        }

        directProviderTemplates.keys.forEach { sourceName ->
            statuses.putIfAbsent(sourceName, "unknown")
        }

        val hubcapStatus = if (hubcapApiKey.isNullOrBlank()) {
            "unknown"
        } else {
            checkHubcapStatus(appId, hubcapApiKey)
        }
        statuses[HUBCAP_SOURCE_NAME] = hubcapStatus

        statuses.entries
            .map { (name, status) ->
                LuaToolsManifestSource(
                    name = name,
                    displayName = sourceDisplayNames[name] ?: name,
                    status = status,
                    requiresUserKey = name in keyRequiredSources,
                    transport = when {
                        name == HUBCAP_SOURCE_NAME -> LuaToolsProviderTransport.Hubcap
                        directProviderUrl(name, appId) != null -> LuaToolsProviderTransport.Direct
                        else -> LuaToolsProviderTransport.LuaToolsProxy
                    },
                )
            }
            .sortedWith(
                compareByDescending<LuaToolsManifestSource> { it.requiresUserKey }
                    .thenBy { it.displayName.lowercase() },
            )
    }

    data class FastFetchResult(
        val sourceName: String,
        val importedCount: Int,
    )

    /**
     * Best-effort FastFetch using every transport the user has configured.
     *
     * Priority is Hubcap (matching LuaTools' key-gated-first behavior), then known direct providers,
     * then authenticated lua.tools proxy providers. Failures fall through and never block Steam.
     */
    suspend fun fastFetch(
        context: Context,
        appId: Int,
        gameName: String? = null,
        allowCleartextDirect: Boolean = false,
    ): FastFetchResult? = withContext(Dispatchers.IO) {
        require(appId > 0) { "Invalid Steam app id" }
        if (SteamManifestOverrideStore.hasOverrides(context, appId)) {
            return@withContext null
        }

        val credentials = ManifestProviderAuthManager.getCredentials(context)

        credentials.hubcapApiKey?.takeIf { it.isNotBlank() }?.let { key ->
            val available = checkHubcapStatus(appId, key).equals("available", ignoreCase = true)
            if (available) {
                val imported = runCatching {
                    downloadHubcap(
                        context = context,
                        appId = appId,
                        hubcapApiKey = key,
                    )
                }.onFailure {
                    Timber.w(it, "FastFetch Hubcap failed for app %d", appId)
                }.getOrNull()

                if (imported != null && imported > 0) {
                    return@withContext FastFetchResult(
                        sourceName = HUBCAP_SOURCE_NAME,
                        importedCount = imported,
                    )
                }
            }
        }

        val direct = fastFetchDirect(
            context = context,
            appId = appId,
            gameName = gameName,
            allowCleartextDirect = allowCleartextDirect,
        )
        if (direct != null) return@withContext direct

        if (credentials.luaToolsSession != null) {
            val bearer = runCatching {
                ManifestProviderAuthManager.getValidLuaToolsAccessToken(context)
            }.onFailure {
                Timber.w(it, "FastFetch lua.tools session unavailable for app %d", appId)
            }.getOrNull()

            if (!bearer.isNullOrBlank()) {
                val proxySources = runCatching {
                    checkSources(appId)
                }.getOrDefault(emptyList())
                    .filter {
                        it.available && it.transport == LuaToolsProviderTransport.LuaToolsProxy
                    }
                    .sortedBy { it.displayName.lowercase() }

                for (source in proxySources) {
                    val imported = runCatching {
                        downloadProvider(
                            context = context,
                            appId = appId,
                            sourceName = source.name,
                            luaToolsBearerToken = bearer,
                            gameName = gameName,
                        )
                    }.onFailure {
                        Timber.w(
                            it,
                            "FastFetch proxy provider failed app=%d source=%s",
                            appId,
                            source.name,
                        )
                    }.getOrNull() ?: continue

                    if (imported > 0) {
                        return@withContext FastFetchResult(
                            sourceName = source.name,
                            importedCount = imported,
                        )
                    }
                }
            }
        }

        null
    }

    /**
     * Best-effort keyless direct-provider FastFetch helper.
     *
     * Existing manual overrides always win: if the user already imported/pinned anything for this
     * app, this returns null without contacting providers. Only keyless DIRECT providers participate;
     * authenticated lua.tools/Hubcap transports are never invoked implicitly.
     *
     * HTTPS direct providers are preferred. Ryuu's upstream fallback is cleartext HTTP, so it is
     * eligible only when [allowCleartextDirect] is explicitly true.
     */
    suspend fun fastFetchDirect(
        context: Context,
        appId: Int,
        gameName: String? = null,
        allowCleartextDirect: Boolean = false,
    ): FastFetchResult? = withContext(Dispatchers.IO) {
        require(appId > 0) { "Invalid Steam app id" }
        if (SteamManifestOverrideStore.hasOverrides(context, appId)) {
            return@withContext null
        }

        // Do not make FastFetch availability depend on the discovery backend. Direct provider
        // endpoints are authoritative enough: 404/invalid content simply falls through to the next
        // provider and eventually to Steam's normal manifest path.
        val sources = directProviderTemplates.keys
            .filter { sourceName ->
                val url = directProviderUrl(sourceName, appId).orEmpty()
                allowCleartextDirect || !url.startsWith("http://", ignoreCase = true)
            }
            .sortedBy(::directProviderPriority)

        for (sourceName in sources) {
            val url = directProviderUrl(sourceName, appId) ?: continue
            val imported = runCatching {
                downloadProvider(
                    context = context,
                    appId = appId,
                    sourceName = sourceName,
                    gameName = gameName,
                )
            }.onFailure {
                Timber.w(
                    it,
                    "FastFetch provider failed app=%d source=%s",
                    appId,
                    sourceName,
                )
            }.getOrNull() ?: continue

            if (imported > 0) {
                return@withContext FastFetchResult(
                    sourceName = sourceName,
                    importedCount = imported,
                )
            }
        }

        null
    }

    /**
     * Download a provider using the same separation visible in LuaTools:
     *
     * - Ryuu/Sushi: direct provider URLs, no lua.tools bearer required.
     * - Hubcap/Sadie: direct Hubcap endpoint with the user's Hubcap key.
     * - Other dynamic providers: lua.tools proxy, which requires a lua.tools bearer token.
     *
     * Steam credentials are never reused as provider credentials.
     */
    suspend fun downloadProvider(
        context: Context,
        appId: Int,
        sourceName: String,
        luaToolsBearerToken: String? = null,
        hubcapApiKey: String? = null,
        gameName: String? = null,
    ): Int = withContext(Dispatchers.IO) {
        require(appId > 0) { "Invalid Steam app id" }
        require(sourceName.isNotBlank()) { "Provider name is required" }

        when {
            sourceName.equals(HUBCAP_SOURCE_NAME, ignoreCase = true) -> {
                val key = hubcapApiKey
                    ?.takeIf { it.isNotBlank() }
                    ?: ManifestProviderAuthManager.getHubcapApiKey(context)
                    ?: throw IllegalStateException("Hubcap API key is required")
                downloadHubcap(context, appId, key)
            }

            directProviderUrl(sourceName, appId) != null -> {
                val url = directProviderUrl(sourceName, appId)!!
                val request = Request.Builder().url(url).get().build()
                val bytes = executeDownload(request)
                SteamManifestOverrideStore.importArtifact(
                    context = context,
                    appId = appId,
                    fileName = "$appId.zip",
                    bytes = bytes,
                    provenance = ManifestOverrideProvenance(
                        sourceKind = ManifestOverrideSourceKind.DirectProvider,
                        sourceLabel = sourceName,
                    ),
                )
            }

            else -> {
                val bearer = luaToolsBearerToken
                    ?.takeIf { it.isNotBlank() }
                    ?: ManifestProviderAuthManager.getValidLuaToolsAccessToken(context)
                val source = encode(sourceName)
                val game = gameName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "&game_name=${encode(it)}" }
                    .orEmpty()
                val url =
                    "$LUA_TOOLS_API_BASE/api/manifest/download?appid=$appId&source=$source$game"
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $bearer")
                    .get()
                    .build()

                val bytes = executeDownload(request)
                SteamManifestOverrideStore.importArtifact(
                    context = context,
                    appId = appId,
                    fileName = "$appId.zip",
                    bytes = bytes,
                    provenance = ManifestOverrideProvenance(
                        sourceKind = ManifestOverrideSourceKind.LuaToolsProvider,
                        sourceLabel = sourceName,
                    ),
                )
            }
        }
    }

    suspend fun downloadHubcap(
        context: Context,
        appId: Int,
        hubcapApiKey: String,
    ): Int = withContext(Dispatchers.IO) {
        require(appId > 0) { "Invalid Steam app id" }
        require(hubcapApiKey.isNotBlank()) { "Hubcap API key is required" }

        val url = "$HUBCAP_BASE/api/v1/manifest/$appId?api_key=${encode(hubcapApiKey)}"
        val request = Request.Builder().url(url).get().build()

        val bytes = executeDownload(request)
        SteamManifestOverrideStore.importArtifact(
            context = context,
            appId = appId,
            fileName = "$appId.zip",
            bytes = bytes,
            provenance = ManifestOverrideProvenance(
                sourceKind = ManifestOverrideSourceKind.Hubcap,
                sourceLabel = "Sadie (Hubcap)",
            ),
        )
    }

    private fun checkHubcapStatus(appId: Int, key: String): String {
        return runCatching {
            val request = Request.Builder()
                .url("$HUBCAP_BASE/api/v1/status/$appId")
                .header("Authorization", "Bearer $key")
                .get()
                .build()
            Net.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use "unavailable"
                val json = JSONObject(response.body?.string().orEmpty())
                if (json.optBoolean("manifest_file_exists", false)) "available" else "unavailable"
            }
        }.getOrDefault("unknown")
    }

    private fun executeDownload(request: Request): ByteArray {
        Net.http.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "Provider request failed with HTTP ${response.code}"
            }
            val body = response.body ?: error("Provider returned an empty response")
            val declared = body.contentLength()
            require(declared < 0L || declared <= MAX_PROVIDER_BYTES) {
                "Provider package is too large"
            }

            val output = ByteArrayOutputStream(
                declared.takeIf { it in 1..MAX_PROVIDER_BYTES }?.toInt() ?: 32 * 1024,
            )
            body.byteStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    require(total <= MAX_PROVIDER_BYTES) { "Provider package is too large" }
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray()
        }
    }

    private fun directProviderPriority(sourceName: String): Int = when {
        sourceName.equals("Ryuu", ignoreCase = true) -> 0
        sourceName.equals("Sushi", ignoreCase = true) -> 1
        else -> 100
    }

    internal fun directProviderUrl(sourceName: String, appId: Int): String? {
        if (appId <= 0) return null
        val entry = directProviderTemplates.entries.firstOrNull {
            it.key.equals(sourceName, ignoreCase = true)
        } ?: return null
        return entry.value.replace("<appid>", appId.toString())
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}
