package app.gamenative.utils

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SteamManifestOverrideStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val appId = 480

    @After
    fun cleanup() {
        SteamManifestOverrideStore.clear(context, appId)
    }

    @Test
    fun providerZipImportsMatchingLuaAndIgnoresOtherFiles() {
        val lua = """
            addappid(480)
            setManifestid(481, "123456789", 987654)
        """.trimIndent()

        val zip = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { archive ->
                archive.putNextEntry(ZipEntry("payload/$appId.lua"))
                archive.write(lua.toByteArray())
                archive.closeEntry()

                archive.putNextEntry(ZipEntry("payload/readme.txt"))
                archive.write("ignored".toByteArray())
                archive.closeEntry()
            }
            output.toByteArray()
        }

        val imported = SteamManifestOverrideStore.importArtifact(
            context = context,
            appId = appId,
            fileName = "$appId.zip",
            bytes = zip,
        )

        assertEquals(1, imported)
        val overrides = SteamManifestOverrideStore.load(context, appId)
        assertEquals(123456789L, overrides[481]?.manifestId)
        assertEquals(987654L, overrides[481]?.sizeOnDisk)
        assertTrue(SteamManifestOverrideStore.hasOverrides(context, appId))
    }
}
