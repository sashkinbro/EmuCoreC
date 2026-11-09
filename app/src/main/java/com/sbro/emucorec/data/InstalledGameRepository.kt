package com.sbro.emucorec.data

import android.content.Context
import android.util.Log
import com.sbro.emucorec.core.EmulatorStorage
import com.sbro.emucorec.core.Ps3SfoParser
import com.sbro.emucorec.core.Ps3Core
import com.sbro.emucorec.core.Ps3Runtime
import org.json.JSONObject
import java.io.File

class InstalledGameRepository {
    fun loadInstalledGames(context: Context): List<InstalledPs3Game> {
        // The library scan walks user-picked folders and parses their contents.
        // Any of those steps can throw on garbage input (unreadable files,
        // malformed metadata, permission flips mid-walk), and an uncaught
        // exception here would take the whole app down from a background scan
        // coroutine. Never propagate: log and fall back to an empty library.
        return runCatching {
            loadInstalledGamesUnsafe(context)
        }.getOrElse { error ->
            Log.e("InstalledGameRepository", "Game library scan failed", error)
            emptyList()
        }
    }

    private fun loadInstalledGamesUnsafe(context: Context): List<InstalledPs3Game> {
        val customFolders = AppPreferences(context).gameDirectories.mapNotNull { raw ->
            val resolved = com.sbro.emucorec.core.DocumentPathResolver.resolveDirectoryPath(context, raw)
                ?: com.sbro.emucorec.core.DocumentPathResolver.resolveFilePath(context, raw)
                ?: raw
            File(resolved).takeIf { it.exists() }
        }
        if (customFolders.size < AppPreferences(context).gameDirectories.size) {
            Log.w(
                "InstalledGameRepository",
                "Some game folders could not be resolved to existing paths: " +
                    "${AppPreferences(context).gameDirectories.size} configured, " +
                    "${customFolders.size} usable"
            )
        }

        val customGameDirs = customFolders.flatMap { folder ->
            if (findParamSfo(folder) != null) {
                listOf(folder)
            } else {
                val directChildren = safeListFiles(folder).filter { it.isDirectory }
                val directMatches = directChildren.filter { findParamSfo(it) != null }
                val nestedMatches = directChildren.flatMap { child ->
                    if (findParamSfo(child) != null) emptyList()
                    else safeListFiles(child).filter { it.isDirectory && findParamSfo(it) != null }
                }
                val allFound = directMatches + nestedMatches
                if (allFound.isNotEmpty()) allFound else if (findParamSfo(folder) != null) listOf(folder) else emptyList()
            }
        }

        val customIsoGames = customFolders.flatMap { folder ->
            val directFiles = safeListFiles(folder).filter { it.isFile && com.sbro.emucorec.core.Ps3IsoParser.isIsoImage(it) }
            val nestedFiles = safeListFiles(folder).filter { it.isDirectory }.flatMap { sub ->
                safeListFiles(sub).filter { it.isFile && com.sbro.emucorec.core.Ps3IsoParser.isIsoImage(it) }
            }
            (directFiles + nestedFiles).mapNotNull { isoFile ->
                com.sbro.emucorec.core.Ps3IsoParser.parse(context, isoFile)
            }
        }

        customGameDirs.forEach { dir ->
            val sfo = findParamSfo(dir) ?: return@forEach
            val metadata = Ps3SfoParser.parse(sfo)
            val tid = metadata.titleId?.takeIf(String::isNotBlank) ?: return@forEach
            FolderGames.link(context, dir.absolutePath, tid)
        }

        customIsoGames.forEach { game ->
            FolderGames.link(context, game.installPath, game.titleId)
        }

        val searchRoots = EmulatorStorage.knownStorageRoots(context).flatMap { storageRoot ->
            listOf(
                File(storageRoot, "ps3/config/dev_hdd0/game"),
                File(storageRoot, "ps3/config/games"),
                File(storageRoot, "ps3/config/dev_hdd0/disc"),
                File(storageRoot, "ps3/directboot"),
                File(storageRoot, "config/dev_hdd0/game"),
                File(storageRoot, "config/games"),
                File(storageRoot, "config/dev_hdd0/disc"),
                File(storageRoot, "directboot")
            )
        }.plus(
            listOf(
                EmulatorStorage.hdd0GameRoot(context),
                EmulatorStorage.discGamesRoot(context),
                EmulatorStorage.hdd0DiscRoot(context),
                FolderGames.linkRoot(context)
            )
        ).distinctBy { it.absolutePath }

        val candidateGameDirs = searchRoots
            .flatMap { root -> safeListFiles(root).filter { it.isDirectory || FolderGames.isLink(it) } }
            .distinctBy { it.absolutePath }

        val installedFromDirs = candidateGameDirs
            .mapNotNull { directory ->
                if (directory.isFile && com.sbro.emucorec.core.Ps3IsoParser.isIsoImage(directory)) {
                    return@mapNotNull com.sbro.emucorec.core.Ps3IsoParser.parse(context, directory)
                }
                readDirectoryGame(context, directory)
            }

        val customFolderDirGames = customGameDirs.mapNotNull { directory ->
            if (directory.isFile && com.sbro.emucorec.core.Ps3IsoParser.isIsoImage(directory)) {
                return@mapNotNull com.sbro.emucorec.core.Ps3IsoParser.parse(context, directory)
                    ?.copy(isCustomFolderGame = true)
            }
            readDirectoryGame(context, directory)?.copy(isCustomFolderGame = true)
        }

        val directBootGames = FolderGames.entries(context).mapNotNull { (_, targetPath) ->
            val target = File(targetPath)
            if (target.isFile && com.sbro.emucorec.core.Ps3IsoParser.isIsoImage(target)) {
                com.sbro.emucorec.core.Ps3IsoParser.parse(context, target)
                    ?.copy(isCustomFolderGame = true)
            } else null
        }

        val markedCustomIsoGames = customIsoGames.map { it.copy(isCustomFolderGame = true) }

        val installed = InstalledGameCandidateMerger.merge(
            installedFromDirs + markedCustomIsoGames + directBootGames + customFolderDirGames
        )

        val covers = Ps3CatalogRepository(context.applicationContext)
            .findCoverUrls(installed)
        return installed
            .map { game ->
                game.copy(catalogCoverUrl = covers[game.titleId] ?: covers[normalizeSerial(game.titleId)])
            }
            .sortedBy { it.title.lowercase() }
    }

    fun findByTitleId(context: Context, titleId: String): InstalledPs3Game? {
        return loadInstalledGames(context).firstOrNull { it.titleId.equals(titleId, ignoreCase = true) }
    }

    fun deleteByTitleId(context: Context, titleId: String): Boolean {
        val safeTitleId = titleId.trim().takeIf(::isSafePathSegment) ?: return false
        val matchingFolders = findInstalledGameFolders(context, safeTitleId)
        if (matchingFolders.isEmpty()) return false

        val deleted = mutableListOf<File>()
        val failed = mutableListOf<File>()

        FolderGames.removeDirectBootByTitleId(context, safeTitleId)

        EmulatorStorage.knownStorageRoots(context).forEach { storageRoot ->
            val ps3Root = File(storageRoot, "ps3")
            deleteInstalledGameFiles(
                context = context,
                ps3Root = ps3Root,
                titleSegment = safeTitleId,
                deleted = deleted,
                failed = failed
            )
        }

        return findInstalledGameFolders(context, safeTitleId).isEmpty()
    }

    /** listFiles() throws SecurityException on directories the app lost access to mid-scan. */
    private fun safeListFiles(directory: File): List<File> =
        runCatching { directory.listFiles()?.toList() ?: emptyList() }
            .getOrElse { error ->
                Log.w("InstalledGameRepository", "Could not list ${directory.absolutePath}", error)
                emptyList()
            }

    private fun findParamSfo(directory: File): File? {
        val direct = File(directory, "PARAM.SFO")
        if (direct.isFile) return direct
        val disc = File(directory, "PS3_GAME/PARAM.SFO")
        if (disc.isFile) return disc
        val usrdir = File(directory, "USRDIR/PARAM.SFO")
        if (usrdir.isFile) return usrdir
        return null
    }

    private fun findIcon(directory: File): File? {
        val direct = File(directory, "ICON0.PNG")
        if (direct.isFile) return direct
        val disc = File(directory, "PS3_GAME/ICON0.PNG")
        if (disc.isFile) return disc
        return null
    }

    private fun readDirectoryGame(context: Context, directory: File): InstalledPs3Game? {
        val metadataDirectory = if (findParamSfo(directory) != null) directory else
            EmulatorStorage.paramSfoPath(context, directory.name).parentFile ?: return null
        val game = readInstalledFolder(metadataDirectory) { trial ->
            runCatching {
                if (!Ps3Runtime.ensureInitialized(context)) return@runCatching null
                val info = JSONObject(Ps3Core.instance.probeDiscInfo(trial.absolutePath, ""))
                info.optString("metadataRoot").takeIf(String::isNotBlank)?.let(::File)
            }.getOrNull()
        } ?: return null
        val fallbackIcon = EmulatorStorage.iconPath(context, game.titleId).takeIf(File::isFile)
        return game.copy(installPath = directory.absolutePath, iconPath = game.iconPath ?: fallbackIcon?.absolutePath)
    }

    private fun findInstalledGameFolders(context: Context, titleId: String): Set<File> {
        val searchSubdirs = listOf(
            "ps3/config/dev_hdd0/game",
            "ps3/config/games",
            "ps3/config/dev_hdd0/disc",
            "ps3/directboot"
        )
        return EmulatorStorage.knownStorageRoots(context)
            .flatMap { storageRoot -> searchSubdirs.map { File(storageRoot, it) } }
            .plus(listOf(FolderGames.linkRoot(context)))
            .flatMap { appRoot -> safeListFiles(appRoot).filter { it.isDirectory || FolderGames.isLink(it) } }
            .filter { directory ->
                directory.name.equals(titleId, ignoreCase = true) ||
                    findParamSfo(directory)?.let(Ps3SfoParser::parse)?.titleId
                        ?.equals(titleId, ignoreCase = true) == true
            }
            .toSet()
    }

    private fun deleteInstalledGameFiles(
        context: Context,
        ps3Root: File,
        titleSegment: String,
        deleted: MutableList<File>,
        failed: MutableList<File>
    ) {
        if (!isSafePathSegment(titleSegment)) return

        val directBootLink = File(FolderGames.linkRoot(context), titleSegment)
        if (FolderGames.isLink(directBootLink) || directBootLink.exists()) {
            FolderGames.removeDirectBootByTitleId(context, titleSegment)
            deleted += directBootLink
        }

        listOf(
            "config/dev_hdd0/game/$titleSegment",
            "config/games/$titleSegment",
            "config/dev_hdd0/disc/$titleSegment",
            "directboot/$titleSegment"
        ).forEach { relativePath ->
            val target = File(ps3Root, relativePath)
            if (FolderGames.isLink(target)) {
                target.delete()
                deleted += target
            } else {
                deleteRecursively(target, deleted, failed)
            }
        }
    }

    private fun deleteRecursively(
        target: File,
        deleted: MutableList<File>,
        failed: MutableList<File>
    ) {
        if (!target.exists()) return
        val removed = runCatching { target.deleteRecursively() }.getOrDefault(false)
        if (removed && !target.exists()) {
            deleted += target
        } else {
            failed += target
        }
    }

    private fun isSafePathSegment(value: String): Boolean {
        return value.isNotBlank() &&
            value != "." &&
            value != ".." &&
            value.none { it == '/' || it == '\\' || it == File.separatorChar }
    }

    private fun normalizeSerial(value: String): String =
        value.trim().uppercase().replace("-", "").replace(" ", "")
}
