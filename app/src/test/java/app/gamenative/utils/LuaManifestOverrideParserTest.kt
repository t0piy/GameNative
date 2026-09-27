package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaManifestOverrideParserTest {

    @Test
    fun parsesActiveManifestPinsAndOptionalSize() {
        val parsed = LuaManifestOverrideParser.parse(
            """
            addappid(123)
            setManifestid(456, "987654321", 123456789)
            setManifestid(789, '18446744073709551615')
            """.trimIndent(),
        )

        assertEquals(2, parsed.size)
        assertEquals(456, parsed[456]?.depotId)
        assertEquals(987654321L, parsed[456]?.manifestId)
        assertEquals(123456789L, parsed[456]?.sizeOnDisk)

        val highBit = parsed[789]?.manifestId
        assertEquals("18446744073709551615", highBit?.let(java.lang.Long::toUnsignedString))
        assertNull(parsed[789]?.sizeOnDisk)
    }

    @Test
    fun ignoresCommentedPinsAndUnrelatedLuaDirectives() {
        val parsed = LuaManifestOverrideParser.parse(
            """
            -- setManifestid(111, "222", 333)
            addappid(444, 1, "not-a-key-we-use")
            addtoken(444, "ignored")
            print("ignored")
            """.trimIndent(),
        )

        assertTrue(parsed.isEmpty())
        assertFalse(parsed.containsKey(111))
    }

    @Test
    fun parsesDirectManifestFileNamesIncludingUnsignedGids() {
        val parsed = SteamManifestOverrideStore.parseManifestFileName(
            "123_18446744073709551615.manifest",
        )

        assertEquals(123, parsed?.depotId)
        assertEquals(
            "18446744073709551615",
            parsed?.manifestId?.let(java.lang.Long::toUnsignedString),
        )
        assertNull(SteamManifestOverrideStore.parseManifestFileName("bad-name.manifest"))
        assertNull(SteamManifestOverrideStore.parseManifestFileName("0_1.manifest"))
    }

    @Test
    fun recognizesRawSteamManifestMagic() {
        assertTrue(
            SteamManifestOverrideStore.isRawSteamManifest(
                byteArrayOf(0xD0.toByte(), 0x17, 0xF6.toByte(), 0x71, 0x00),
            ),
        )
        assertFalse(
            SteamManifestOverrideStore.isRawSteamManifest(
                byteArrayOf(0x50, 0x4B, 0x03, 0x04),
            ),
        )
    }

    @Test
    fun lastActivePinForDepotWins() {
        val parsed = LuaManifestOverrideParser.parse(
            """
            setManifestid(100, "200", 300)
            setManifestid(100, "201", 301) -- newer pin
            """.trimIndent(),
        )

        assertEquals(1, parsed.size)
        assertEquals(201L, parsed[100]?.manifestId)
        assertEquals(301L, parsed[100]?.sizeOnDisk)
    }
}
