package app.gamenative.service.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameDownloadServiceTest {

    @Test
    fun `shared depot completion cannot hide missing main game depot`() {
        val unresolved = GameDownloadService.unresolvedSelectedDepotIds(
            selectedDepotIds = listOf(1250651, 229000),
            completedDepotIds = setOf(229000),
        )

        assertEquals(listOf(1250651), unresolved)
    }

    @Test
    fun `app can complete only when every selected depot completed`() {
        val unresolved = GameDownloadService.unresolvedSelectedDepotIds(
            selectedDepotIds = listOf(1250651, 229000),
            completedDepotIds = setOf(1250651, 229000),
        )

        assertTrue(unresolved.isEmpty())
    }
}
