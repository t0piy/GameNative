package app.gamenative.utils

import app.gamenative.service.SteamService
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun providerZipKeepsValidManifestWhenLuaHasNoPins() {
        fun u32(value: Long): ByteArray = byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte(),
        )
        fun varint(value: ULong): ByteArray {
            var remaining = value
            val out = mutableListOf<Byte>()
            do {
                var next = (remaining and 0x7fuL).toByte()
                remaining = remaining shr 7
                if (remaining != 0uL) next = (next.toInt() or 0x80).toByte()
                out += next
            } while (remaining != 0uL)
            return out.toByteArray()
        }

        val metadata =
            varint(8uL) + varint(481uL) +
                varint(16uL) + varint(123456789uL) +
                varint(40uL) + varint(987654uL)
        val rawManifest =
            u32(0x71F617D0L) + u32(0) +
                u32(0x1F4812BEL) + u32(metadata.size.toLong()) + metadata +
                u32(0x32C415ABL)

        val zip = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { archive ->
                archive.putNextEntry(ZipEntry("payload/$appId.lua"))
                archive.write("addappid($appId)".toByteArray())
                archive.closeEntry()

                archive.putNextEntry(ZipEntry("payload/481_123456789.manifest"))
                archive.write(rawManifest)
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
        val override = SteamManifestOverrideStore.load(context, appId)[481]
        assertEquals(123456789L, override?.manifestId)
        assertEquals(987654L, override?.sizeOnDisk)
    }

    @Test
    fun invalidProviderZipDoesNotLeavePartialManifestOverrides() {
        fun u32(value: Long): ByteArray = byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte(),
        )
        fun varint(value: ULong): ByteArray {
            var remaining = value
            val out = mutableListOf<Byte>()
            do {
                var next = (remaining and 0x7fuL).toByte()
                remaining = remaining shr 7
                if (remaining != 0uL) next = (next.toInt() or 0x80).toByte()
                out += next
            } while (remaining != 0uL)
            return out.toByteArray()
        }

        val metadata =
            varint(8uL) + varint(481uL) +
                varint(16uL) + varint(123456789uL) +
                varint(40uL) + varint(987654uL)
        val validManifest =
            u32(0x71F617D0L) + u32(0) +
                u32(0x1F4812BEL) + u32(metadata.size.toLong()) + metadata

        val zip = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { archive ->
                archive.putNextEntry(ZipEntry("payload/481_123456789.manifest"))
                archive.write(validManifest)
                archive.closeEntry()

                archive.putNextEntry(ZipEntry("payload/482_999.manifest"))
                archive.write("corrupt".toByteArray())
                archive.closeEntry()
            }
            output.toByteArray()
        }

        assertThrows(IllegalArgumentException::class.java) {
            SteamManifestOverrideStore.importArtifact(
                context = context,
                appId = appId,
                fileName = "$appId.zip",
                bytes = zip,
            )
        }

        assertFalse(SteamManifestOverrideStore.hasOverrides(context, appId))
        assertTrue(SteamManifestOverrideStore.load(context, appId).isEmpty())
    }

    @Test
    fun persistsAndReplacesOverrideProvenancePerDepot() {
        SteamManifestOverrideStore.saveLua(
            context = context,
            appId = appId,
            luaText = """setManifestid(481, "111", 1000)""",
            provenance = ManifestOverrideProvenance(
                sourceKind = ManifestOverrideSourceKind.DirectProvider,
                sourceLabel = "Ryuu",
                importedAtEpochMillis = 1_700_000_000_000L,
            ),
        )

        val first = SteamManifestOverrideStore.load(context, appId)[481]
        assertEquals(111L, first?.manifestId)
        assertEquals(ManifestOverrideSourceKind.DirectProvider, first?.provenance?.sourceKind)
        assertEquals("Ryuu", first?.provenance?.sourceLabel)
        assertEquals(1_700_000_000_000L, first?.provenance?.importedAtEpochMillis)

        SteamManifestOverrideStore.saveLua(
            context = context,
            appId = appId,
            luaText = """setManifestid(481, "222", 2000)""",
            provenance = ManifestOverrideProvenance(
                sourceKind = ManifestOverrideSourceKind.DirectProvider,
                sourceLabel = "Sushi",
                importedAtEpochMillis = 1_700_000_100_000L,
            ),
        )

        val replaced = SteamManifestOverrideStore.load(context, appId)[481]
        assertEquals(222L, replaced?.manifestId)
        assertEquals("Sushi", replaced?.provenance?.sourceLabel)
        assertEquals(1_700_000_100_000L, replaced?.provenance?.importedAtEpochMillis)
    }

    @Test
    fun dlcNamespaceWinsOverParentFallback() {
        val baseAppId = 1_222_670
        val dlcAppId = 1_622_460
        val depotId = 1_622_461

        SteamManifestOverrideStore.clear(context, baseAppId)
        SteamManifestOverrideStore.clear(context, dlcAppId)
        try {
            SteamManifestOverrideStore.saveLua(
                context = context,
                appId = baseAppId,
                luaText = """setManifestid($depotId, "111", 1000)""",
                provenance = ManifestOverrideProvenance(
                    sourceKind = ManifestOverrideSourceKind.LocalFile,
                    sourceLabel = "legacy-parent",
                ),
            )

            val fallback = SteamManifestOverrideStore.findForDepot(
                context = context,
                parentAppId = baseAppId,
                depotId = depotId,
                dlcAppId = dlcAppId,
                depotFromApp = SteamService.INVALID_APP_ID,
                invalidAppId = SteamService.INVALID_APP_ID,
            )
            assertEquals(111L, fallback?.manifestId)
            assertEquals(baseAppId, fallback?.namespaceAppId)

            SteamManifestOverrideStore.saveLua(
                context = context,
                appId = dlcAppId,
                luaText = """setManifestid($depotId, "222", 2000)""",
                provenance = ManifestOverrideProvenance(
                    sourceKind = ManifestOverrideSourceKind.DirectProvider,
                    sourceLabel = "Ryuu",
                ),
            )

            val ownedDlcOverride = SteamManifestOverrideStore.findForDepot(
                context = context,
                parentAppId = baseAppId,
                depotId = depotId,
                dlcAppId = dlcAppId,
                depotFromApp = SteamService.INVALID_APP_ID,
                invalidAppId = SteamService.INVALID_APP_ID,
            )
            assertEquals(222L, ownedDlcOverride?.manifestId)
            assertEquals(2000L, ownedDlcOverride?.sizeOnDisk)
            assertEquals(dlcAppId, ownedDlcOverride?.namespaceAppId)
            assertEquals("Ryuu", ownedDlcOverride?.provenance?.sourceLabel)
        } finally {
            SteamManifestOverrideStore.clear(context, baseAppId)
            SteamManifestOverrideStore.clear(context, dlcAppId)
        }
    }

    @Test
    fun resolvesOwningNamespaceForDlcAndSharedDepots() {
        val baseAppId = 1_222_670
        val dlcAppId = 1_622_460
        val sharedAppId = 228_980
        val invalid = SteamService.INVALID_APP_ID

        assertEquals(
            dlcAppId,
            SteamManifestOverrideStore.owningAppId(
                parentAppId = baseAppId,
                dlcAppId = dlcAppId,
                depotFromApp = sharedAppId,
                invalidAppId = invalid,
            ),
        )
        assertEquals(
            sharedAppId,
            SteamManifestOverrideStore.owningAppId(
                parentAppId = baseAppId,
                dlcAppId = invalid,
                depotFromApp = sharedAppId,
                invalidAppId = invalid,
            ),
        )
        assertEquals(
            baseAppId,
            SteamManifestOverrideStore.owningAppId(
                parentAppId = baseAppId,
                dlcAppId = invalid,
                depotFromApp = invalid,
                invalidAppId = invalid,
            ),
        )
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
