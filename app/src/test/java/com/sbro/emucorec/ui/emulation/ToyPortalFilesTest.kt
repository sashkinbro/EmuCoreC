package com.sbro.emucorec.ui.emulation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToyPortalFilesTest {
    @Test
    fun importingSameNameNeverOverwritesAChangedFigure() {
        val dir = Files.createTempDirectory("portal-test").toFile()
        try {
            val original = ByteArray(1024) { 1 }
            val changed = ByteArray(1024) { 2 }
            val first = ToyPortalFiles.store(dir, "../figure.sky", ByteArrayInputStream(original), 1024)!!
            val duplicate = ToyPortalFiles.store(dir, "figure.sky", ByteArrayInputStream(original), 1024)!!
            val second = ToyPortalFiles.store(dir, "figure.sky", ByteArrayInputStream(changed), 1024)!!
            assertEquals(first, duplicate)
            assertNotEquals(first, second)
            assertArrayEquals(original, first.readBytes())
            assertArrayEquals(changed, second.readBytes())
            assertEquals(dir.canonicalFile, first.canonicalFile.parentFile)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun rejectsFilesOutsideFigureSizeLimits() {
        val dir = Files.createTempDirectory("portal-test").toFile()
        try {
            assertNull(ToyPortalFiles.store(dir, "tiny.bin", ByteArrayInputStream(ByteArray(10)), 180))
            assertNull(ToyPortalFiles.store(dir, "large.bin", ByteArrayInputStream(ByteArray(65537)), 180))
            assertEquals(0, dir.listFiles()?.size ?: 0)
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun archiveImportKeepsValidFiguresInsideTheTargetDirectory() {
        val dir = Files.createTempDirectory("portal-test").toFile()
        try {
            val archive = ByteArrayOutputStream()
            ZipOutputStream(archive).use { zip ->
                zip.putNextEntry(ZipEntry("../../figure.sky"))
                zip.write(ByteArray(1024) { 3 })
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("notes.txt"))
                zip.write(ByteArray(10))
                zip.closeEntry()
            }
            val files = ToyPortalFiles.importArchive(dir, ByteArrayInputStream(archive.toByteArray()), 1024, setOf("sky"))
            assertEquals(1, files.size)
            assertEquals(dir.canonicalFile, files.single().canonicalFile.parentFile)
            assertEquals(1024L, files.single().length())
        } finally { dir.deleteRecursively() }
    }
}
