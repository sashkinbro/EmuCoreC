package com.sbro.emucorec.ui.emulation

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

internal data class ToyPortalCharacter(val id: Int, val variant: Int, val name: String)

internal fun parsePortalCharacters(lines: Sequence<String>, kind: Int): List<ToyPortalCharacter> = lines.mapNotNull { line ->
    val parts = line.split('\t')
    val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
    val variant = if (kind == 2) 0 else parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
    val name = parts.getOrNull(if (kind == 2) 1 else 2).orEmpty()
    if (name.isBlank() || name.startsWith("Unknown") || (kind == 0 && id in 601..631)) null else ToyPortalCharacter(id, variant, name)
}.toList()

internal object ToyPortalFiles {
    fun importArchive(directory: File, input: InputStream, minimumSize: Int, extensions: Set<String>): List<File> {
        val result = mutableListOf<File>()
        var totalBytes = 0L
        ZipInputStream(input).use { zip ->
            for (entryIndex in 0 until 4096) {
                val entry = zip.nextEntry ?: break
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (output.size() <= 65536) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                }
                val bytes = output.toByteArray()
                totalBytes += bytes.size
                if (bytes.size > 65536 || totalBytes > 16 * 1024 * 1024) break
                val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                if (!entry.isDirectory && name.substringAfterLast('.', "").lowercase() in extensions) {
                    store(directory, name, ByteArrayInputStream(bytes), minimumSize)?.let(result::add)
                }
            }
        }
        return result
    }

    @Synchronized
    fun store(directory: File, offeredName: String, input: InputStream, minimumSize: Int): File? {
        val name = offeredName.substringAfterLast('/').substringAfterLast('\\').map {
            if (it < ' ' || it in "\\/:*?\"<>|") '_' else it
        }.joinToString("").trim().take(100).ifBlank { "figure.bin" }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (output.size() <= 65536) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) break
                output.write(byte)
            } else {
                output.write(buffer, 0, count)
            }
        }
        val bytes = output.toByteArray()
        if (bytes.size !in minimumSize..65536) return null
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val extension = name.substringAfterLast('.', "bin")
        val base = name.substringBeforeLast('.', name)
        var target = File(directory, name)
        var suffix = 1
        while (target.exists()) {
            if (target.isFile && target.length() == bytes.size.toLong() && target.readBytes().contentEquals(bytes)) return target
            target = File(directory, "$base (${++suffix}).$extension")
        }
        val temporary = File.createTempFile("figure-", ".part", directory)
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) return null
        } finally {
            temporary.delete()
        }
        return target
    }
}
