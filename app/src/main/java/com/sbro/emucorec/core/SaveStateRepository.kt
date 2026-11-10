package com.sbro.emucorec.core

import android.content.Context
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class SaveStateRepository internal constructor(private val root: File) {
    constructor(context: Context) : this(File(EmulatorStorage.ps3Root(context), "config/savestates"))
    fun gamesWithSlots(): List<String> = root.listFiles().orEmpty()
        .filter { it.isDirectory && TITLE_ID.matches(it.name) }
        .map { it.name }
        .filter { runCatching { listSlots(it).isNotEmpty() }.getOrDefault(false) }
        .sorted()

    fun listSlots(titleId: String): List<SaveStateSlot> {
        directory(titleId)
        return SLOT_ORDER.mapNotNull { slot ->
            stateFile(titleId, slot)?.let { file ->
                SaveStateSlot(slot, file.lastModified() / 1000, file.length(), file.absolutePath,
                    thumbnailFile(titleId, slot).takeIf(File::isFile)?.absolutePath,
                    thumbnailFile(titleId, slot).lastModified())
            }
        }
    }

    fun stateFile(titleId: String, slot: Int): File? = variants(titleId, slot).firstOrNull(File::isFile)

    fun thumbnailFile(titleId: String, slot: Int): File {
        require(slot in SLOT_ORDER)
        return ownedFile(directory(titleId), "slot$slot.jpg")
    }

    fun delete(titleId: String, slot: Int): Boolean {
        val states = variants(titleId, slot)
        val deleted = states.all { !it.exists() || it.delete() }
        if (deleted) {
            states.forEach { state -> ownedFile(state.parentFile!!, state.name + ".integrity").let { if (it.exists()) it.delete() } }
            thumbnailFile(titleId, slot).let { if (it.exists()) it.delete() }
        }
        return deleted
    }

    private fun variants(titleId: String, slot: Int): List<File> {
        require(slot in SLOT_ORDER)
        val directory = directory(titleId)
        return listOf(".zst", ".gz", "").map { ownedFile(directory, "slot$slot.SAVESTAT$it") }
    }

    private fun directory(titleId: String): File {
        require(TITLE_ID.matches(titleId))
        val directory = File(root, "$titleId/emucorec_slots")
        require(directory.canonicalFile.parentFile?.parentFile == root.canonicalFile)
        return directory
    }

    private fun ownedFile(directory: File, name: String): File = File(directory, name).also {
        require(it.canonicalFile.parentFile == directory.canonicalFile)
    }
    companion object {
        const val QUICK_SLOT = 0
        val SLOT_ORDER = (0 until 10).toList()
        private val TITLE_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

fun formatSaveStateTimestamp(timestamp: Long): String = DateFormat.getDateTimeInstance(
    DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

fun formatSaveStateSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return if (index == 0) "${value.toLong()} ${units[index]}"
    else String.format(Locale.getDefault(), "%.1f %s", value, units[index])
}
