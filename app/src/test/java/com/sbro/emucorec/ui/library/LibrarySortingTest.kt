package com.sbro.emucorec.ui.library

import com.sbro.emucorec.data.InstalledPs3Game
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortingTest {
    private fun game(id: String, title: String) = InstalledPs3Game(
        id, title, null, null, null, null, null, null, "/games/$id",
    )

    @Test fun titleSortingIsAccentAndCaseInsensitiveWithStableIdentityTies() {
        val games = listOf(game("C", "zoo"), game("B", "Éclair"), game("A", "eclair"))
        assertEquals(listOf("A", "B", "C"), sortLibraryGames(games, LibrarySortOption.TITLE_ASC).map { it.titleId })
        assertEquals(listOf("C", "A", "B"), sortLibraryGames(games, LibrarySortOption.TITLE_DESC).map { it.titleId })
        assertEquals(listOf("C", "B", "A"), games.map { it.titleId })
    }

    @Test fun sizeAndUpdatedOrderingUseTheSelectedMetricAndTitleToBreakTies() {
        val games = listOf(game("A", "Alpha"), game("B", "Beta"), game("C", "Charlie"))
        val metadata = mapOf(
            "/games/A" to LibrarySortMetadata(10, 30),
            "/games/B" to LibrarySortMetadata(30, 10),
            "/games/C" to LibrarySortMetadata(30, 20),
        )
        fun order(mode: LibrarySortOption) = sortLibraryGames(games, mode, metadata).map { it.titleId }
        assertEquals(listOf("A", "C", "B"), order(LibrarySortOption.SIZE_DESC))
        assertEquals(listOf("B", "C", "A"), order(LibrarySortOption.SIZE_ASC))
        assertEquals(listOf("B", "C", "A"), order(LibrarySortOption.RECENT_DESC))
        assertEquals(listOf("A", "B", "C"), order(LibrarySortOption.RECENT_ASC))
    }

    @Test fun unknownStoredModeFallsBackToTitleOrder() {
        assertEquals(LibrarySortOption.TITLE_ASC, LibrarySortOption.fromPreference("PLATFORM"))
        assertEquals(LibrarySortOption.TITLE_ASC, LibrarySortOption.fromPreference(null))
        assertEquals(LibrarySortOption.SIZE_DESC, LibrarySortOption.fromPreference("SIZE_DESC"))
    }
}
