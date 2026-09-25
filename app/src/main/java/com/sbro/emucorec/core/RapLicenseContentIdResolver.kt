package com.sbro.emucorec.core

import com.sbro.emucorec.data.InstalledPs3Game
import java.io.File
import java.nio.charset.StandardCharsets

/** Resolves the NPDRM content id that RPCS3 requires as a RAP filename. */
internal object RapLicenseContentIdResolver {
    private const val CONTENT_ID_LENGTH = 36
    private const val SCAN_CHUNK_SIZE = 64 * 1024
    private const val MAX_SCAN_BYTES = 16L * 1024L * 1024L
    private val contentIdRegex = Regex("[A-Z]{2}[0-9]{4}-[A-Z0-9]{9}_[0-9]{2}-[A-Z0-9]{16}")

    fun resolve(rapFile: File, installedGames: List<InstalledPs3Game>): String? =
        resolveContentId(rapFile, installedGames)?.contentId

    /**
     * Canonical name to suggest when [resolve] fails, so the error can tell the user exactly what
     * to rename the file to. Null when no installed game looks like a match.
     */
    fun suggestedFileName(rapFile: File, installedGames: List<InstalledPs3Game>): String? =
        resolveContentId(rapFile, installedGames)?.contentId?.plus(".rap")

    private data class ResolvedLicense(val contentId: String)

    private fun resolveContentId(rapFile: File, installedGames: List<InstalledPs3Game>): ResolvedLicense? {
        canonicalContentId(rapFile.nameWithoutExtension)?.let { return ResolvedLicense(it) }

        val rapLabel = normalizeLabel(rapFile.nameWithoutExtension)
        if (rapLabel.isBlank()) return null

        val matchingGames = installedGames.filter { game -> matches(rapLabel, game) }
        if (matchingGames.isEmpty()) return null

        val resolved = matchingGames.flatMap(::contentIdsFor).distinct()
        return resolved.singleOrNull()?.let(::ResolvedLicense)
    }

    /** Loose match so a renamed license (e.g. "BullyL", "GTA3L") still finds its game. */
    private fun matches(rapLabel: String, game: InstalledPs3Game): Boolean {
        val title = normalizeLabel(game.title)
        val titleId = normalizeLabel(game.titleId)
        if (title.isNotBlank()) {
            if (rapLabel == title || rapLabel == title + "l" ||
                rapLabel.startsWith(title) || title.startsWith(rapLabel)) {
                return true
            }
        }
        return titleId.isNotBlank() && rapLabel.contains(titleId)
    }

    private fun contentIdsFor(game: InstalledPs3Game): List<String> {
        val found = (listOfNotNull(canonicalContentId(game.contentId)) +
            findEboot(game.installPath)?.let(::readContentIds).orEmpty()).distinct()
        val titleId = game.titleId.trim().uppercase()
        val matching = if (titleId.isNotBlank()) found.filter { it.contains(titleId) } else emptyList()
        // Disc-based and PS2-classic packages carry a title id that does not appear in their
        // content id, so fall back to whatever the metadata and eboot actually name.
        return matching.ifEmpty { found }
    }

    internal fun canonicalContentId(value: String?): String? {
        val normalized = value?.trim()?.uppercase().orEmpty()
        return normalized.takeIf(contentIdRegex::matches)
    }

    internal fun readContentIds(file: File): List<String> {
        if (!file.isFile) return emptyList()
        val found = linkedSetOf<String>()
        runCatching {
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(SCAN_CHUNK_SIZE)
                var tail = ByteArray(0)
                var scanned = 0L
                while (scanned < MAX_SCAN_BYTES) {
                    val requested = minOf(buffer.size.toLong(), MAX_SCAN_BYTES - scanned).toInt()
                    val count = input.read(buffer, 0, requested)
                    if (count <= 0) break
                    scanned += count

                    val bytes = ByteArray(tail.size + count)
                    tail.copyInto(bytes)
                    buffer.copyInto(bytes, tail.size, 0, count)
                    val text = String(bytes, StandardCharsets.US_ASCII)
                    contentIdRegex.findAll(text).forEach { found += it.value }

                    val overlap = minOf(CONTENT_ID_LENGTH - 1, bytes.size)
                    tail = bytes.copyOfRange(bytes.size - overlap, bytes.size)
                }
            }
        }
        return found.toList()
    }

    private fun findEboot(installPath: String): File? {
        val root = File(installPath)
        if (!root.isDirectory) return null
        return listOf(
            File(root, "USRDIR/EBOOT.BIN"),
            File(root, "EBOOT.BIN"),
            File(root, "PS3_GAME/USRDIR/EBOOT.BIN"),
            File(root, "PS3_GAME/EBOOT.BIN"),
        ).firstOrNull(File::isFile)
    }

    private fun normalizeLabel(value: String): String =
        value.lowercase().filter(Char::isLetterOrDigit)
}
