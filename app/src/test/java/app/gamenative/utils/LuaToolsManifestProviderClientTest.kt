package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LuaToolsManifestProviderClientTest {

    @Test
    fun resolvesKeylessDirectProviderUrls() {
        assertEquals(
            "http://167.235.229.108/1145350",
            LuaToolsManifestProviderClient.directProviderUrl("Ryuu", 1145350),
        )
        assertEquals(
            "https://raw.githubusercontent.com/sushi-dev55-alt/sushitools-games-repo-alt/refs/heads/main/1145350.zip",
            LuaToolsManifestProviderClient.directProviderUrl("Sushi", 1145350),
        )
    }

    @Test
    fun unknownProviderRequiresAnotherTransport() {
        assertNull(
            LuaToolsManifestProviderClient.directProviderUrl("Skyflare", 1145350),
        )
        assertNull(
            LuaToolsManifestProviderClient.directProviderUrl("Ryuu", 0),
        )
    }
}
