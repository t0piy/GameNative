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
    fun parsesRawSteamManifestMetadataForProgressSizing() {
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
            varint(8uL) + varint(123uL) +
                varint(16uL) + varint(456uL) +
                varint(40uL) + varint(789uL)
        val raw =
            u32(0x71F617D0L) + u32(0) +
                u32(0x1F4812BEL) + u32(metadata.size.toLong()) + metadata +
                u32(0x32C415ABL) + u32(0)

        val parsed = SteamManifestOverrideStore.parseRawSteamManifestMetadata(raw)

        assertEquals(123, parsed?.depotId)
        assertEquals(456L, parsed?.manifestId)
        assertEquals(789L, parsed?.sizeOnDisk)
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
