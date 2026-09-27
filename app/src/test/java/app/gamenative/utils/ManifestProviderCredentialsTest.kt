package app.gamenative.utils

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestProviderCredentialsTest {

    @Test
    fun readsExpiryFromSupabaseStyleJwt() {
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"alg":"HS256","typ":"JWT"}""".toByteArray(),
        )
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            JSONObject().put("exp", 2_000_000_000L).toString().toByteArray(),
        )
        val token = "$header.$payload.signature"

        assertEquals(
            2_000_000_000L,
            ManifestProviderAuthManager.jwtExpiryEpochSeconds(token),
        )
    }

    @Test
    fun invalidJwtHasNoExpiry() {
        assertNull(ManifestProviderAuthManager.jwtExpiryEpochSeconds("not-a-jwt"))
    }

    @Test
    fun validatesHubcapKeyFormatLikeUpstream() {
        val valid = "smm_" + "a".repeat(96)
        assertTrue(ManifestProviderAuthManager.isValidHubcapKeyFormat(valid))
        assertFalse(
            ManifestProviderAuthManager.isValidHubcapKeyFormat("smm_" + "A".repeat(96)),
        )
        assertFalse(
            ManifestProviderAuthManager.isValidHubcapKeyFormat("smm_" + "a".repeat(95)),
        )
    }
}
