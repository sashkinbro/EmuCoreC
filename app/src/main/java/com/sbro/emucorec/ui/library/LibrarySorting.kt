package com.sbro.emucorec.ui.library

import android.content.Context
import com.sbro.emucorec.data.InstalledPs3Game
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The single-platform subset of EmuCoreX's HomeSortOption. */
internal enum class LibrarySortOption {
    TITLE_ASC, TITLE_DESC, RECENT_DESC, RECENT_ASC, SIZE_DESC, SIZE_ASC;

    val requiresMetadata get() = this != TITLE_ASC && this != TITLE_DESC

    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: TITLE_ASC
    }
}

internal class LibraryOptionsPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("emucorec_library_prefs", Context.MODE_PRIVATE)
    var sortOption: LibrarySortOption
        get() = LibrarySortOption.fromPreference(prefs.getString("sort_option", null))
        set(value) { prefs.edit().putString("sort_option", value.name).apply() }
}

internal data class LibrarySortMetadata(val modified: Long, val size: Long)

internal fun sortLibraryGames(
    games: List<InstalledPs3Game>,
    option: LibrarySortOption,
    metadata: Map<String, LibrarySortMetadata> = emptyMap(),
): List<InstalledPs3Game> {
    val titleOrder = compareBy<InstalledPs3Game> { librarySortToken(it.title) }
        .thenBy { it.titleId }.thenBy { it.installPath }
    val comparator = when (option) {
        LibrarySortOption.TITLE_ASC -> titleOrder
        LibrarySortOption.TITLE_DESC -> compareByDescending<InstalledPs3Game> { librarySortToken(it.title) }
            .thenBy { it.titleId }.thenBy { it.installPath }
        LibrarySortOption.RECENT_DESC -> compareByDescending<InstalledPs3Game> { metadata[it.installPath]?.modified ?: 0 }.then(titleOrder)
        LibrarySortOption.RECENT_ASC -> compareBy<InstalledPs3Game> { metadata[it.installPath]?.modified ?: 0 }.then(titleOrder)
        LibrarySortOption.SIZE_DESC -> compareByDescending<InstalledPs3Game> { metadata[it.installPath]?.size ?: 0 }.then(titleOrder)
        LibrarySortOption.SIZE_ASC -> compareBy<InstalledPs3Game> { metadata[it.installPath]?.size ?: 0 }.then(titleOrder)
    }
    return games.sortedWith(comparator)
}

/** File stat and optional directory traversal always run on the screen's IO worker. */
internal suspend fun readLibrarySortMetadata(
    games: List<InstalledPs3Game>, option: LibrarySortOption,
): Map<String, LibrarySortMetadata> = buildMap {
    val includeSize = option == LibrarySortOption.SIZE_ASC || option == LibrarySortOption.SIZE_DESC
    for (game in games) {
        currentCoroutineContext().ensureActive()
        val root = File(game.installPath)
        val size = if (!includeSize) 0L else if (root.isFile) root.length() else {
            // Canonical roots handle app-owned direct-boot links; walkTopDown does not
            // follow directory symlinks inside a game, avoiding cycles and unrelated data.
            val canonicalRoot = runCatching { root.canonicalFile }.getOrDefault(root)
            var bytes = 0L
            canonicalRoot.walkTopDown().onEnter { directory ->
                directory == canonicalRoot || !java.nio.file.Files.isSymbolicLink(directory.toPath())
            }.forEach { file ->
                currentCoroutineContext().ensureActive()
                if (file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath())) bytes += file.length()
            }
            bytes
        }
        put(game.installPath, LibrarySortMetadata(root.lastModified(), size))
    }
}

private fun librarySortToken(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    .lowercase(Locale.ROOT).trim()
