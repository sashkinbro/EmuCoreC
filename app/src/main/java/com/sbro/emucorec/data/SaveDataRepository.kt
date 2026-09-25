package com.sbro.emucorec.data

import android.content.Context
import android.net.Uri
import com.sbro.emucorec.core.EmulatorStorage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class Ps3SaveDataEntry(
    val saveId: String,
    val titleId: String?,
    val title: String,
    val iconPath: String?,
    val coverUrl: String? = null,
    val path: String,
    val sizeBytes: Long,
    val updatedAtMillis: Long,
    val installed: Boolean
)

data class Ps3SaveDataTarget(
    val saveId: String,
    val titleId: String,
    val title: String,
    val iconPath: String?
)

sealed class SaveDataImportResult {
    data class Success(val saveId: String) : SaveDataImportResult()
    data object EmptyArchive : SaveDataImportResult()
    data object UnsafeArchive : SaveDataImportResult()
    data object UnknownTarget : SaveDataImportResult()
    data class Failure(val error: Throwable) : SaveDataImportResult()
}

sealed class SaveDataBulkImportResult {
    data class Success(val restoredCount: Int) : SaveDataBulkImportResult()
    data object EmptyArchive : SaveDataBulkImportResult()
    data object UnsafeArchive : SaveDataBulkImportResult()
    data class Failure(val error: Throwable) : SaveDataBulkImportResult()
}

class SaveDataRepository {
    private val installedGameRepository = InstalledGameRepository()

    fun list(context: Context): List<Ps3SaveDataEntry> {
        val installedGames = installedGameRepository.loadInstalledGames(context)
        val gamesBySaveId = installedGames
            .mapNotNull { game -> game.saveDataId?.takeIf(String::isNotBlank)?.let { it to game } }
            .toMap()
        return saveRoots(context)
            .flatMap { saveRoot -> saveRoot.listFiles().orEmpty().toList() }
            .filter { it.isDirectory && it.listFiles().orEmpty().isNotEmpty() }
            .distinctBy { it.name }
            .map { directory ->
                val saveId = directory.name
                val game = gamesBySaveId[saveId] ?: installedGames.firstOrNull { it.titleId == saveId }
                Ps3SaveDataEntry(
                    saveId = saveId,
                    titleId = game?.titleId,
                    title = game?.title ?: saveId,
                    iconPath = game?.iconPath,
                    coverUrl = game?.catalogCoverUrl,
                    path = directory.absolutePath,
                    sizeBytes = directory.directorySize(),
                    updatedAtMillis = directory.latestModified(),
                    installed = game != null
                )
            }
            .sortedWith(compareByDescending<Ps3SaveDataEntry> { it.installed }.thenBy { it.title.lowercase() })
    }

    fun findForTitleId(context: Context, titleId: String): Ps3SaveDataEntry? {
        val saveId = targetForTitleId(context, titleId)?.saveId ?: titleId
        return list(context).firstOrNull { it.saveId == saveId || it.titleId == titleId }
    }

    fun targetForTitleId(context: Context, titleId: String): Ps3SaveDataTarget? {
        val game = installedGameRepository.findByTitleId(context, titleId) ?: return null
        return Ps3SaveDataTarget(
            saveId = game.saveDataId?.takeIf(String::isNotBlank) ?: game.titleId,
            titleId = game.titleId,
            title = game.title,
            iconPath = game.iconPath
        )
    }

    fun delete(context: Context, saveId: String): Boolean {
        val targets = saveRoots(context).map { root -> File(root, saveId) }.filter(File::exists)
        if (targets.isEmpty()) return true
        return targets.all { target -> runCatching { target.deleteRecursively() }.getOrDefault(false) }
    }

    fun exportToZip(context: Context, saveId: String, destination: Uri): Result<Unit> = runCatching {
        val source = saveRoots(context)
            .map { root -> File(root, saveId) }
            .firstOrNull { it.isDirectory }
            ?: File(EmulatorStorage.hdd0SaveDataRoot(context), saveId)
        require(source.isDirectory) { "Save data not found." }
        context.contentResolver.openOutputStream(destination)?.use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                source.zipInto(zip, "$saveId/")
            }
        } ?: error("Cannot open destination.")
    }

    fun exportAllToZip(context: Context, destination: Uri): Result<Int> = runCatching {
        val saves = list(context)
        require(saves.isNotEmpty()) { "No save data found." }
        context.contentResolver.openOutputStream(destination)?.use { output ->
            ZipOutputStream(output.buffered()).use { zip ->
                saves.forEach { save ->
                    val source = File(save.path)
                    if (source.isDirectory) {
                        source.zipInto(zip, "${save.saveId}/")
                    }
                }
            }
        } ?: error("Cannot open destination.")
        saves.size
    }

    fun importFromZip(context: Context, source: Uri, targetSaveId: String? = null): SaveDataImportResult {
        val importRoot = File(EmulatorStorage.cacheRoot(context), "save_import").apply {
            deleteRecursively()
            mkdirs()
        }
        val extractedRoot = File(importRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        return try {
            val safe = context.contentResolver.openInputStream(source)?.use { input ->
                ZipInputStream(input.buffered()).use { zip -> zip.extractSafelyTo(extractedRoot) }
            } ?: return SaveDataImportResult.Failure(IllegalStateException("Cannot open archive."))
            if (!safe) return SaveDataImportResult.UnsafeArchive
            if (extractedRoot.walkTopDown().none { it.isFile }) return SaveDataImportResult.EmptyArchive

            val singleRoot = extractedRoot.listFiles().orEmpty().singleOrNull { it.isDirectory }
                ?.takeIf { root -> extractedRoot.listFiles().orEmpty().all { it == root } }
            val saveId = targetSaveId?.takeIf(String::isNotBlank)
                ?: singleRoot?.name?.takeIf(String::isNotBlank)
                ?: return SaveDataImportResult.UnknownTarget
            val contentRoot = when {
                targetSaveId != null && singleRoot != null -> singleRoot
                singleRoot != null && targetSaveId == null -> singleRoot
                else -> extractedRoot
            }
            replaceSaveDirectory(context, saveId, contentRoot)
            SaveDataImportResult.Success(saveId)
        } catch (error: Throwable) {
            SaveDataImportResult.Failure(error)
        } finally {
            importRoot.deleteRecursively()
        }
    }

    fun importAllFromZip(context: Context, source: Uri): SaveDataBulkImportResult {
        val importRoot = File(EmulatorStorage.cacheRoot(context), "save_import_all").apply {
            deleteRecursively()
            mkdirs()
        }
        val extractedRoot = File(importRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        return try {
            val safe = context.contentResolver.openInputStream(source)?.use { input ->
                ZipInputStream(input.buffered()).use { zip -> zip.extractSafelyTo(extractedRoot) }
            } ?: return SaveDataBulkImportResult.Failure(IllegalStateException("Cannot open archive."))
            if (!safe) return SaveDataBulkImportResult.UnsafeArchive

            val contentRoot = extractedRoot.listFiles().orEmpty().singleOrNull { it.isDirectory && it.name == "saves" }
                ?.takeIf { root -> extractedRoot.listFiles().orEmpty().all { it == root } }
                ?: extractedRoot
            val saveDirectories = contentRoot.listFiles().orEmpty()
                .filter { it.isDirectory && it.walkTopDown().any(File::isFile) }
                .filter { isSafeSaveId(it.name) }
            if (saveDirectories.isEmpty()) return SaveDataBulkImportResult.EmptyArchive

            replaceSaveDirectories(context, saveDirectories.associateBy { it.name })
            SaveDataBulkImportResult.Success(saveDirectories.size)
        } catch (error: Throwable) {
            SaveDataBulkImportResult.Failure(error)
        } finally {
            importRoot.deleteRecursively()
        }
    }

    private fun replaceSaveDirectory(context: Context, saveId: String, source: File) {
        val saveRoot = EmulatorStorage.hdd0SaveDataRoot(context)
        val target = File(saveRoot, saveId)
        val backup = File(EmulatorStorage.cacheRoot(context), "save_backup/$saveId-${System.currentTimeMillis()}")
        runCatching {
            if (target.exists()) {
                backup.parentFile?.mkdirs()
                target.copyRecursively(backup, overwrite = true)
                target.deleteRecursively()
            }
            target.mkdirs()
            source.copyRecursively(target, overwrite = true)
            backup.deleteRecursively()
        }.onFailure { error ->
            target.deleteRecursively()
            if (backup.exists()) {
                backup.copyRecursively(target, overwrite = true)
                backup.deleteRecursively()
            }
            throw error
        }
    }

    private fun replaceSaveDirectories(context: Context, sourcesBySaveId: Map<String, File>) {
        val saveRoot = EmulatorStorage.hdd0SaveDataRoot(context).apply { mkdirs() }
        val backupRoot = File(EmulatorStorage.cacheRoot(context), "save_bulk_restore/${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }
        val touchedSaveIds = mutableListOf<String>()
        try {
            sourcesBySaveId.forEach { (saveId, source) ->
                val target = File(saveRoot, saveId)
                touchedSaveIds += saveId
                if (target.exists()) {
                    target.copyRecursively(File(backupRoot, saveId), overwrite = true)
                    target.deleteRecursively()
                }
                target.mkdirs()
                source.copyRecursively(target, overwrite = true)
            }
            backupRoot.deleteRecursively()
        } catch (error: Throwable) {
            touchedSaveIds.forEach { saveId ->
                val target = File(saveRoot, saveId)
                target.deleteRecursively()
                val backup = File(backupRoot, saveId)
                if (backup.exists()) {
                    backup.copyRecursively(target, overwrite = true)
                }
            }
            backupRoot.deleteRecursively()
            throw error
        }
    }

    private fun saveRoots(context: Context): List<File> {
        val roots = mutableListOf<File>()
        val baseDirs = mutableListOf<File>()
        baseDirs.add(File(EmulatorStorage.ps3Root(context), "config/dev_hdd0/home"))
        baseDirs.add(File(EmulatorStorage.ps3Root(context), "dev_hdd0/home"))
        baseDirs.add(File(EmulatorStorage.storageRoot(context), "ps3/config/dev_hdd0/home"))
        baseDirs.add(File(EmulatorStorage.storageRoot(context), "ps3/dev_hdd0/home"))
        baseDirs.add(File(EmulatorStorage.storageRoot(context), "config/dev_hdd0/home"))
        baseDirs.add(File(EmulatorStorage.storageRoot(context), "dev_hdd0/home"))
        baseDirs.add(File(EmulatorStorage.runtimeRoot(context), "config/dev_hdd0/home"))
        baseDirs.add(File(EmulatorStorage.runtimeRoot(context), "dev_hdd0/home"))
        EmulatorStorage.knownStorageRoots(context).forEach { storageRoot ->
            baseDirs.add(File(storageRoot, "ps3/config/dev_hdd0/home"))
            baseDirs.add(File(storageRoot, "ps3/dev_hdd0/home"))
            baseDirs.add(File(storageRoot, "config/dev_hdd0/home"))
            baseDirs.add(File(storageRoot, "dev_hdd0/home"))
        }

        baseDirs.distinctBy { it.absolutePath }.forEach { home ->
            if (home.isDirectory) {
                home.listFiles().orEmpty().filter(File::isDirectory).forEach { userDir ->
                    val saveDir = File(userDir, "savedata")
                    if (saveDir.isDirectory) roots.add(saveDir)
                }
            }
        }
        roots.add(EmulatorStorage.hdd0SaveDataRoot(context))
        roots.add(EmulatorStorage.hdd0SaveDataRoot(context, "00"))
        return roots.distinctBy { it.absolutePath }
    }

    private fun isSafeSaveId(value: String): Boolean {
        return value.isNotBlank() &&
            value != "." &&
            value != ".." &&
            value.none { it == '/' || it == '\\' || it == ':' }
    }

    private fun ZipInputStream.extractSafelyTo(destination: File): Boolean {
        val destinationPath = destination.canonicalFile.toPath()
        var entry = nextEntry
        while (entry != null) {
            val outputFile = File(destination, entry.name).canonicalFile
            if (!outputFile.toPath().startsWith(destinationPath)) {
                closeEntry()
                return false
            }
            if (entry.isDirectory) {
                outputFile.mkdirs()
            } else {
                outputFile.parentFile?.mkdirs()
                FileOutputStream(outputFile).use { output -> copyTo(output) }
            }
            closeEntry()
            entry = nextEntry
        }
        return true
    }

    private fun File.zipInto(zip: ZipOutputStream, entryPrefix: String) {
        listFiles().orEmpty().forEach { child ->
            val entryName = entryPrefix + child.name
            if (child.isDirectory) {
                zip.putNextEntry(ZipEntry("$entryName/"))
                zip.closeEntry()
                child.zipInto(zip, "$entryName/")
            } else {
                zip.putNextEntry(ZipEntry(entryName))
                FileInputStream(child).use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun File.directorySize(): Long =
        walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun File.latestModified(): Long =
        walkTopDown().maxOfOrNull { it.lastModified() } ?: lastModified()
}
