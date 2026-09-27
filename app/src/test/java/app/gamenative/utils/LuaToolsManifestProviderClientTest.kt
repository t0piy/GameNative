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
    fun directProvidersCanBeAttemptedEvenWhenDiscoveryIsUnknown() {
        val direct = LuaToolsManifestSource(
            name = "Ryuu",
            status = "unknown",
            transport = LuaToolsProviderTransport.Direct,
        )
        val proxied = LuaToolsManifestSource(
            name = "Skyflare",
            status = "unknown",
            transport = LuaToolsProviderTransport.LuaToolsProxy,
        )

        assertEquals(true, direct.canAttemptDownload)
        assertEquals(false, proxied.canAttemptDownload)
    }

    @Test
    fun buildsLuaToolsDlcMetadataUrlWithBaseApp() {
        assertEquals(
            "https://lua.tools/api/dlc/generate?appid=1622460&base=1222670" +
                "&game_name=Example+Pack",
            LuaToolsManifestProviderClient.luaToolsDlcGenerateUrl(
                baseAppId = 1_222_670,
                dlcAppId = 1_622_460,
                gameName = "Example Pack",
            ),
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
