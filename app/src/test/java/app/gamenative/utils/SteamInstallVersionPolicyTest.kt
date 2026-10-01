package app.gamenative.utils

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamInstallVersionPolicyTest {
    private val installed = mapOf(489831 to 100uL, 489832 to 200uL)
    private val latest = mapOf(489831 to 101uL, 489832 to 201uL)

    @Test
    fun normalSteamInstallStillOffersAnUpdate() {
        assertTrue(SteamInstallVersionPolicy.isStoreUpdatePending(installed, latest, emptyMap()))
    }

    @Test
    fun matchingSteamInstallDoesNotOfferAnUpdate() {
        assertFalse(SteamInstallVersionPolicy.isStoreUpdatePending(installed, installed, emptyMap()))
    }

    @Test
    fun customManifestInstallIsNotComparedWithTheSteamBranch() {
        assertFalse(SteamInstallVersionPolicy.isStoreUpdatePending(installed, latest, installed))
    }

    @Test
    fun aNewlyImportedPinDoesNotBecomeAForcedUpdate() {
        assertFalse(SteamInstallVersionPolicy.isStoreUpdatePending(installed, latest, mapOf(489831 to 300uL)))
    }

    @Test
    fun unrelatedUninstalledDlcPinDoesNotDisableSteamUpdates() {
        assertTrue(SteamInstallVersionPolicy.isStoreUpdatePending(installed, latest, mapOf(999999 to 100uL)))
    }

    @Test
    fun removingOverridesRestoresSteamUpdateChecks() {
        assertFalse(SteamInstallVersionPolicy.isStoreUpdatePending(installed, latest, installed))
        assertTrue(SteamInstallVersionPolicy.isStoreUpdatePending(installed, latest, emptyMap()))
    }

    @Test
    fun addedByAppIdAloneIsNotAnExternalManifestInstall() {
        assertFalse(SteamInstallVersionPolicy.isExternallyManaged(installed, emptyMap()))
    }

    @Test
    fun anInstalledDlcNamespacePinMakesTheCombinedInstallManual() {
        assertTrue(SteamInstallVersionPolicy.isExternallyManaged(installed, mapOf(489832 to 200uL)))
    }

    @Test
    fun noCommittedFilesMeansNoUpdate() {
        assertFalse(SteamInstallVersionPolicy.isStoreUpdatePending(emptyMap(), latest, installed))
    }

    @Test
    fun invalidManifestIdsNeverCountAsUpdatesOrPins() {
        val invalid = mapOf(489831 to 0uL, 489832 to Long.MAX_VALUE.toULong())
        assertFalse(SteamInstallVersionPolicy.isExternallyManaged(installed, invalid))
        assertFalse(SteamInstallVersionPolicy.isStoreUpdatePending(invalid, latest, emptyMap()))
        assertFalse(SteamInstallVersionPolicy.isStoreUpdatePending(installed, invalid, emptyMap()))
    }

    @Test
    fun unknownStoreDepotDoesNotInventAnUpdate() {
        assertFalse(SteamInstallVersionPolicy.isStoreUpdatePending(installed, emptyMap(), emptyMap()))
    }

    @Test
    fun journalPreservesNumericAndQuotedUint64IdsExactly() {
        val parsed = SteamInstallVersionPolicy.parseInstalledManifestIds(
            """{"installedManifestIDs":{"489831":18446744073709551615,"489832":"9223372036854775808"}}""",
        )
        assertEquals(ULong.MAX_VALUE, parsed[489831])
        assertEquals(9223372036854775808uL, parsed[489832])
    }

    @Test
    fun journalRejectsZeroSentinelOverflowNegativeAndFractionalValues() {
        val parsed = SteamInstallVersionPolicy.parseInstalledManifestIds(
            """{"installedManifestIDs":{"1":0,"2":9223372036854775807,"3":18446744073709551616,"4":-5,"5":1.5,"6":1e3,"7":123}}""",
        )
        assertEquals(mapOf(7 to 123uL), parsed)
    }

    @Test
    fun emptyOrTruncatedJournalDoesNotCreateInstalledIds() {
        assertEquals(emptyMap<Int, ULong>(), SteamInstallVersionPolicy.parseInstalledManifestIds("{}"))
        assertEquals(emptyMap<Int, ULong>(), SteamInstallVersionPolicy.parseInstalledManifestIds("garbage"))
        assertEquals(emptyMap<Int, ULong>(), SteamInstallVersionPolicy.parseInstalledManifestIds("""{"installedManifestIDs":{"1":123"""))
    }

    @Test
    fun cachedNewerManifestDoesNotReplaceTheCommittedJournalVersion() = withCache { dir ->
        File(dir, "depot.config").writeText("""{"installedManifestIDs":{"489831":100}}""")
        File(dir, "489831_101.manifest").writeText("staged, not installed")
        assertEquals(mapOf(489831 to 100uL), SteamInstallVersionPolicy.readInstalledManifestIds(dir))
    }

    @Test
    fun emptyJournalRemainsAuthoritativeEvenWithAStagedManifest() = withCache { dir ->
        File(dir, "depot.config").writeText("""{"installedManifestIDs":{}}""")
        File(dir, "489831_100.manifest").writeText("staged, not installed")
        assertTrue(SteamInstallVersionPolicy.readInstalledManifestIds(dir).isEmpty())
    }

    @Test
    fun unfinishedJournalNeverFallsBackToTheCachedTarget() = withCache { dir ->
        File(dir, "depot.config").writeText("""{"installedManifestIDs":{"489831":9223372036854775807}}""")
        File(dir, "489831_100.manifest").writeText("staged, not installed")
        assertTrue(SteamInstallVersionPolicy.readInstalledManifestIds(dir).isEmpty())
    }

    @Test
    fun unreadableOrCorruptJournalNeverInfersCompletionFromCache() = withCache { dir ->
        File(dir, "depot.config").writeText("torn JSON")
        File(dir, "489831_100.manifest").writeText("staged, not installed")
        assertTrue(SteamInstallVersionPolicy.readInstalledManifestIds(dir).isEmpty())
    }

    @Test
    fun legacyInstallWithoutJournalAcceptsOnlyAnUnambiguousCachedVersion() = withCache { dir ->
        File(dir, "489831_100.manifest").writeText("legacy manifest")
        assertEquals(mapOf(489831 to 100uL), SteamInstallVersionPolicy.readInstalledManifestIds(dir))
        File(dir, "489831_101.manifest").writeText("ambiguous second version")
        assertTrue(SteamInstallVersionPolicy.readInstalledManifestIds(dir).isEmpty())
    }

    @Test
    fun legacyCacheIgnoresDirectoriesAndUnrelatedFiles() = withCache { dir ->
        File(dir, "489831_100.manifest").mkdirs()
        File(dir, "489832_200.progress").writeText("not a manifest")
        File(dir, "489833_0.manifest").writeText("invalid ID")
        assertTrue(SteamInstallVersionPolicy.readInstalledManifestIds(dir).isEmpty())
    }

    @Test
    fun acfContainsTheCommittedCustomVersionNotTheAdvertisedVersion() = withCache { dir ->
        File(dir, "depot.config").writeText("""{"installedManifestIDs":{"489831":100}}""")
        val ids = SteamInstallVersionPolicy.readInstalledManifestIds(dir)
        val acf = SteamInstallVersionPolicy.installedDepotsAcf(
            ids.mapValues { SteamInstallVersionPolicy.ClientDepot(it.value, 1234L) },
        )
        assertTrue(acf.contains("\"manifest\"\t\t\"100\""))
        assertFalse(acf.contains("\"manifest\"\t\t\"101\""))
        assertEquals(100uL, SteamInstallVersionPolicy.readInstalledManifestIds(dir)[489831])
    }

    @Test
    fun acfUsesUnsignedManifestIdsAndNonNegativeSizes() {
        val acf = SteamInstallVersionPolicy.installedDepotsAcf(
            mapOf(489831 to SteamInstallVersionPolicy.ClientDepot(ULong.MAX_VALUE, -1L)),
        )
        assertTrue(acf.contains("\"manifest\"\t\t\"18446744073709551615\""))
        assertTrue(acf.contains("\"size\"\t\t\"0\""))
        assertFalse(acf.contains("\"-1\""))
    }

    @Test
    fun matchingInstalledAcfPreservesItsKnownBuildId() {
        assertEquals(123L, SteamInstallVersionPolicy.clientBuildId(installed, latest, 999L, installed, 123L))
    }

    @Test
    fun currentManifestSetCanUseItsStoreBuildId() {
        assertEquals(999L, SteamInstallVersionPolicy.clientBuildId(latest, latest, 999L))
    }

    @Test
    fun oldCustomVersionDoesNotPretendToBeTheLatestSteamBuild() {
        assertEquals(0L, SteamInstallVersionPolicy.clientBuildId(installed, latest, 999L))
        assertEquals(0L, SteamInstallVersionPolicy.clientBuildId(installed, latest, 999L, latest, 999L))
    }

    @Test
    fun emptyInstallDoesNotInventABuild() {
        assertEquals(0L, SteamInstallVersionPolicy.clientBuildId(emptyMap(), latest, 999L))
    }

    private inline fun withCache(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("steam-version-policy-test").toFile()
        try {
            test(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
