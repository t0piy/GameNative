package app.gamenative.utils

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

data class LuaToolsProviderSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
) {
    override fun toString(): String =
        "LuaToolsProviderSession(accessToken=[REDACTED], refreshToken=[REDACTED], " +
            "expiresAt=$expiresAtEpochSeconds)"
}

data class ManifestProviderCredentials(
    val luaToolsSession: LuaToolsProviderSession? = null,
    val hubcapApiKey: String? = null,
) {
    override fun toString(): String =
        "ManifestProviderCredentials(luaToolsSession=$luaToolsSession, " +
            "hubcapApiKey=${if (hubcapApiKey.isNullOrBlank()) "null" else "[REDACTED]"})"
}

/**
 * Secure storage for manifest-provider credentials.
 *
 * The JSON record is encrypted with AES-GCM using a non-exportable Android Keystore key. The
 * ciphertext is stored in private SharedPreferences; the key is intentionally not backed up.
 */
class ManifestProviderCredentialStore(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val crypto = ManifestProviderCredentialAesGcm()
    private val lock = Any()

    fun read(): ManifestProviderCredentials = synchronized(lock) {
        val encoded = preferences.getString(CREDENTIALS_KEY, null)
            ?: return@synchronized ManifestProviderCredentials()

        try {
            val ciphertext = Base64.getDecoder().decode(encoded)
            fromJson(JSONObject(crypto.decrypt(ciphertext).toString(Charsets.UTF_8)))
        } catch (error: Exception) {
            Timber.w(
                error,
                "Discarding unreadable manifest-provider credential storage",
            )
            preferences.edit().remove(CREDENTIALS_KEY).commit()
            ManifestProviderCredentials()
        }
    }

    fun write(credentials: ManifestProviderCredentials) = synchronized(lock) {
        val json = toJson(credentials)
        val ciphertext = crypto.encrypt(json.toString().toByteArray(Charsets.UTF_8))
        val encoded = Base64.getEncoder().encodeToString(ciphertext)
        check(preferences.edit().putString(CREDENTIALS_KEY, encoded).commit()) {
            "Unable to persist manifest-provider credentials"
        }
    }

    fun clear() = synchronized(lock) {
        check(preferences.edit().remove(CREDENTIALS_KEY).commit()) {
            "Unable to clear manifest-provider credentials"
        }
    }

    private fun toJson(credentials: ManifestProviderCredentials): JSONObject = JSONObject()
        .put("version", STORAGE_VERSION)
        .apply {
            credentials.luaToolsSession?.let { session ->
                put(
                    "lua_tools",
                    JSONObject()
                        .put("access_token", session.accessToken)
                        .put("refresh_token", session.refreshToken)
                        .put("expires_at", session.expiresAtEpochSeconds),
                )
            }
            credentials.hubcapApiKey?.takeIf { it.isNotBlank() }?.let {
                put("hubcap_api_key", it)
            }
        }

    private fun fromJson(json: JSONObject): ManifestProviderCredentials {
        if (json.optInt("version") != STORAGE_VERSION) return ManifestProviderCredentials()

        val luaTools = json.optJSONObject("lua_tools")?.let { sessionJson ->
            val access = sessionJson.optString("access_token")
            val refresh = sessionJson.optString("refresh_token")
            val expiresAt = sessionJson.optLong("expires_at", 0L)
            if (access.isBlank() || expiresAt <= 0L) {
                null
            } else {
                LuaToolsProviderSession(
                    accessToken = access,
                    refreshToken = refresh,
                    expiresAtEpochSeconds = expiresAt,
                )
            }
        }

        return ManifestProviderCredentials(
            luaToolsSession = luaTools,
            hubcapApiKey = json.optString("hubcap_api_key").takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        private const val PREFERENCES_NAME = "manifest_provider_credentials_secure"
        private const val CREDENTIALS_KEY = "credentials"
        private const val STORAGE_VERSION = 1
    }
}

object ManifestProviderAuthManager {
    private const val LUA_TOOLS_API_BASE = "https://lua.tools"
    private const val SUPABASE_URL = "https://db.lua.tools"

    // Public Supabase anonymous client key. This value is compiled into LuaTools itself and is not
    // a user secret. User access/refresh tokens are stored separately in Android Keystore storage.
    private const val SUPABASE_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJpYXQiOjE3NzYwMzkzNzYsImV4cCI6MTg5MzQ1NjAwMCwicm9sZSI6ImFub24iLCJpc3MiOiJzdXBhYmFzZSJ9." +
            "f_-K38u3odjltP-g_67FVmG32Vg-_-k-lNBvIaVUVBM"

    private val refreshMutex = Mutex()

    fun getCredentials(context: Context): ManifestProviderCredentials =
        ManifestProviderCredentialStore(context).read()

    suspend fun saveManualCredentials(
        context: Context,
        accessToken: String,
        refreshToken: String,
        hubcapApiKey: String,
    ) {
        val access = accessToken.trim()
        val refresh = refreshToken.trim()
        val hubcap = hubcapApiKey.trim()

        if (hubcap.isNotBlank()) {
            require(isValidHubcapKeyFormat(hubcap)) {
                "Hubcap key must start with smm_ and contain 96 lowercase hex characters"
            }
        }

        val now = System.currentTimeMillis() / 1000L
        val session = when {
            access.isBlank() && refresh.isBlank() -> null

            access.isNotBlank() -> {
                val expiresAt = jwtExpiryEpochSeconds(access)
                    ?: throw IllegalArgumentException(
                        "lua.tools access token is not a JWT with an exp claim",
                    )
                if (expiresAt > now + 30L) {
                    LuaToolsProviderSession(
                        accessToken = access,
                        refreshToken = refresh,
                        expiresAtEpochSeconds = expiresAt,
                    )
                } else {
                    require(refresh.isNotBlank()) {
                        "lua.tools access token is expired and no refresh token was provided"
                    }
                    refreshLuaToolsSession(refresh)
                }
            }

            else -> refreshLuaToolsSession(refresh)
        }

        ManifestProviderCredentialStore(context).write(
            ManifestProviderCredentials(
                luaToolsSession = session,
                hubcapApiKey = hubcap.takeIf { it.isNotBlank() },
            ),
        )
    }

    fun clear(context: Context) {
        ManifestProviderCredentialStore(context).clear()
    }

    suspend fun signInWithLuaToolsCode(
        context: Context,
        code: String,
    ): LuaToolsProviderSession = withContext(Dispatchers.IO) {
        val normalized = code.trim().uppercase()
        require(normalized.length == 6) { "lua.tools login code must be 6 characters" }

        val redeemBody = JSONObject()
            .put("code", normalized)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val redeemRequest = Request.Builder()
            .url("$LUA_TOOLS_API_BASE/api/auth/code/redeem")
            .post(redeemBody)
            .build()

        val tokenHash = Net.http.newCall(redeemRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                when (response.code) {
                    404 -> "lua.tools login code is invalid"
                    410 -> "lua.tools login code has expired"
                    else -> "lua.tools login failed with HTTP ${response.code}"
                }
            }
            JSONObject(body).optString("token").takeIf { it.isNotBlank() }
                ?: error("lua.tools login returned no verification token")
        }

        val verifyBody = JSONObject()
            .put("type", "magiclink")
            .put("token_hash", tokenHash)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val verifyRequest = Request.Builder()
            .url("$SUPABASE_URL/auth/v1/verify")
            .header("apikey", SUPABASE_ANON_KEY)
            .post(verifyBody)
            .build()

        val session = Net.http.newCall(verifyRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                "lua.tools session verification failed with HTTP ${response.code}"
            }
            sessionFromSupabaseJson(JSONObject(body))
        }

        val store = ManifestProviderCredentialStore(context)
        val existing = store.read()
        store.write(existing.copy(luaToolsSession = session))
        session
    }

    suspend fun getValidLuaToolsAccessToken(context: Context): String =
        refreshMutex.withLock {
            val store = ManifestProviderCredentialStore(context)
            val credentials = store.read()
            val session = credentials.luaToolsSession
                ?: throw IllegalStateException("lua.tools sign-in is required")

            val now = System.currentTimeMillis() / 1000L
            if (session.expiresAtEpochSeconds > now + 120L) {
                return@withLock session.accessToken
            }

            require(session.refreshToken.isNotBlank()) {
                "lua.tools session expired and no refresh token is stored"
            }

            val refreshed = refreshLuaToolsSession(session.refreshToken)
            store.write(credentials.copy(luaToolsSession = refreshed))
            refreshed.accessToken
        }

    fun getHubcapApiKey(context: Context): String? =
        ManifestProviderCredentialStore(context).read().hubcapApiKey

    private suspend fun refreshLuaToolsSession(refreshToken: String): LuaToolsProviderSession =
        withContext(Dispatchers.IO) {
            val jsonBody = JSONObject()
                .put("refresh_token", refreshToken)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$SUPABASE_URL/auth/v1/token?grant_type=refresh_token")
                .header("apikey", SUPABASE_ANON_KEY)
                .post(jsonBody)
                .build()

            Net.http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                check(response.isSuccessful) {
                    "lua.tools session refresh failed with HTTP ${response.code}"
                }

                sessionFromSupabaseJson(
                    json = JSONObject(body),
                    fallbackRefreshToken = refreshToken,
                )
            }
        }

    private fun sessionFromSupabaseJson(
        json: JSONObject,
        fallbackRefreshToken: String = "",
    ): LuaToolsProviderSession {
        val access = json.optString("access_token")
        val refresh = json.optString("refresh_token").ifBlank { fallbackRefreshToken }
        val expiresIn = json.optLong("expires_in", 0L)
        require(access.isNotBlank()) { "lua.tools response returned no access token" }

        val jwtExpiry = jwtExpiryEpochSeconds(access)
        val expiresAt = jwtExpiry
            ?: (System.currentTimeMillis() / 1000L + expiresIn).takeIf { expiresIn > 0L }
            ?: error("lua.tools response returned no token expiry")

        return LuaToolsProviderSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAtEpochSeconds = expiresAt,
        )
    }

    internal fun isValidHubcapKeyFormat(key: String): Boolean =
        Regex("""^smm_[0-9a-f]{96}$""").matches(key)

    internal fun jwtExpiryEpochSeconds(token: String): Long? = runCatching {
        val parts = token.split('.')
        require(parts.size >= 2)
        val payload = parts[1]
        val padding = "=".repeat((4 - payload.length % 4) % 4)
        val decoded = Base64.getUrlDecoder().decode(payload + padding)
        JSONObject(decoded.toString(Charsets.UTF_8)).optLong("exp", 0L).takeIf { it > 0L }
    }.getOrNull()
}

private class ManifestProviderCredentialAesGcm {
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }
    private val keyLock = Any()

    fun encrypt(plaintext: ByteArray): ByteArray {
        require(plaintext.isNotEmpty())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(ASSOCIATED_DATA)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > IV_LENGTH_BYTES + TAG_LENGTH_BYTES)
        val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, iv),
        )
        cipher.updateAAD(ASSOCIATED_DATA)
        return cipher.doFinal(ciphertext)
    }

    private fun getOrCreateKey(): SecretKey = synchronized(keyLock) {
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        existing?.secretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE,
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "gamenative_manifest_provider_credentials_aes_gcm_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BYTES = 16
        private const val TAG_LENGTH_BITS = TAG_LENGTH_BYTES * 8
        private val ASSOCIATED_DATA =
            "GameNative:ManifestProviderCredentials:v1".toByteArray(Charsets.UTF_8)
    }
}
