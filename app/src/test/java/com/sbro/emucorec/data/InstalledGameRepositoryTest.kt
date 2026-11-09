package com.sbro.emucorec.data

import com.sbro.emucorec.core.Ps3SfoParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class InstalledGameRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun unlockedTrialRefreshesTitleAndIconWithoutChangingItsLaunchRoot() {
        val directory = tempFolder.newFolder("trial")
        val c00 = File(directory, "C00").apply { mkdirs() }
        val base = File(directory, "PARAM.SFO").apply {
            writeBytes(createParamSfoBytes("NPUB30505", "Harmony of Despair Demo", "HG", "UP0101-NPUB30505_00-TEST000000000000"))
        }
        File(directory, "ICON0.PNG").writeBytes(byteArrayOf(1))
        File(c00, "PARAM.SFO").writeBytes(createParamSfoBytes("NPUB30505", "Harmony of Despair", "HG"))
        val fullIcon = File(c00, "ICON0.PNG").apply { writeBytes(byteArrayOf(2)) }
        val oldTimestamp = base.lastModified()
        var unlocked = false
        var probes = 0
        fun scan() = readInstalledFolder(directory) { probes++; if (unlocked) c00 else directory }!!
        assertEquals("Harmony of Despair Demo", scan().title)
        unlocked = true
        val full = scan()
        assertEquals("Harmony of Despair", full.title)
        assertEquals(fullIcon.canonicalFile, File(full.iconPath!!).canonicalFile)
        assertEquals(directory.absolutePath, full.installPath)
        assertEquals(oldTimestamp, base.lastModified())
        assertEquals(2, probes)
    }

    @Test
    fun c00WithoutVerifiedUnlockOrValidMetadataKeepsTheTrial() {
        val directory = tempFolder.newFolder("locked-trial")
        val c00 = File(directory, "C00").apply { mkdirs() }
        File(directory, "PARAM.SFO").writeBytes(createParamSfoBytes("NPUB30505", "Demo", "HG", "UP0101-NPUB30505_00-TEST000000000000"))
        File(c00, "PARAM.SFO").writeBytes(createParamSfoBytes("NPUB00000", "Different Game", "HG"))
        assertEquals("Demo", readInstalledFolder(directory)!!.title)
        assertEquals("Demo", readInstalledFolder(directory) { c00 }!!.title)
    }

    @Test
    fun parsesValidParamSfo() {
        val sfoFile = tempFolder.newFile("PARAM.SFO")
        sfoFile.writeBytes(createParamSfoBytes("BLES01234", "Demon's Souls"))

        val parsed = Ps3SfoParser.parse(sfoFile)
        assertEquals("BLES01234", parsed.titleId)
        assertEquals("Demon's Souls", parsed.title)
    }

    @Test
    fun locatesSfoAndIconInDiscStructure() {
        val gameFolder = tempFolder.newFolder("BLES00909")
        val ps3Game = File(gameFolder, "PS3_GAME").apply { mkdirs() }
        val sfoFile = File(ps3Game, "PARAM.SFO")
        val iconFile = File(ps3Game, "ICON0.PNG")
        sfoFile.writeBytes(createParamSfoBytes("BLES00909", "God of War III"))
        iconFile.writeBytes(byteArrayOf(1, 2, 3))

        val parsed = Ps3SfoParser.parse(sfoFile)
        assertEquals("BLES00909", parsed.titleId)
        assertEquals("God of War III", parsed.title)
        assertTrue(iconFile.isFile)
    }

    @Test
    fun locatesSfoAndIconInDirectGameStructure() {
        val gameFolder = tempFolder.newFolder("NPUB30001")
        val sfoFile = File(gameFolder, "PARAM.SFO")
        val iconFile = File(gameFolder, "ICON0.PNG")
        sfoFile.writeBytes(createParamSfoBytes("NPUB30001", "Minecraft PS3"))
        iconFile.writeBytes(byteArrayOf(4, 5, 6))

        val parsed = Ps3SfoParser.parse(sfoFile)
        assertEquals("NPUB30001", parsed.titleId)
        assertEquals("Minecraft PS3", parsed.title)
        assertTrue(iconFile.isFile)
    }

    @Test
    fun locatesSfoInUsrdirStructure() {
        val gameFolder = tempFolder.newFolder("BLUS12345")
        val usrDir = File(gameFolder, "USRDIR").apply { mkdirs() }
        val sfoFile = File(usrDir, "PARAM.SFO")
        sfoFile.writeBytes(createParamSfoBytes("BLUS12345", "Custom Game"))

        val parsed = Ps3SfoParser.parse(sfoFile)
        assertEquals("BLUS12345", parsed.titleId)
        assertEquals("Custom Game", parsed.title)
    }

    @Test
    fun discUpdateUsesNewMetadataButKeepsIsoLaunchSource() {
        val iso = tempFolder.newFile("Grand Theft Auto IV.iso")
        val updateDirectory = tempFolder.newFolder("BLES00229-update")
        val base = game(
            titleId = "BLES00229",
            version = "01.00",
            category = "DG",
            installPath = iso.absolutePath,
            iconPath = "/disc/ICON0.PNG"
        )
        val update = game(
            titleId = "BLES00229",
            version = "01.08",
            category = "GD",
            installPath = updateDirectory.absolutePath,
            iconPath = "/update/ICON0.PNG"
        )

        val merged = InstalledGameCandidateMerger.merge(listOf(update, base)).single()

        assertEquals(iso.absolutePath, merged.installPath)
        assertEquals("01.08", merged.version)
        assertEquals("DG", merged.category)
        assertEquals("/update/ICON0.PNG", merged.iconPath)
    }

    @Test
    fun installedUpdateWithoutBaseGameIsNotListedAsStandaloneGame() {
        val update = game(
            titleId = "BLES00229",
            version = "01.08",
            category = "GD",
            installPath = tempFolder.newFolder("orphan-update").absolutePath
        )

        assertTrue(InstalledGameCandidateMerger.merge(listOf(update)).isEmpty())
    }

    @Test
    fun olderUpdateCannotDowngradeBaseMetadata() {
        val base = game(
            titleId = "NPUB12345",
            version = "02.10",
            category = "HG",
            installPath = tempFolder.newFolder("digital-base").absolutePath
        )
        val staleUpdate = game(
            titleId = "NPUB12345",
            version = "02.09",
            category = "GD",
            installPath = tempFolder.newFolder("stale-update").absolutePath
        )

        val merged = InstalledGameCandidateMerger.merge(listOf(staleUpdate, base)).single()

        assertEquals(base.installPath, merged.installPath)
        assertEquals("02.10", merged.version)
    }

    @Test
    fun versionComparisonUsesNumericComponents() {
        assertTrue(InstalledGameCandidateMerger.compareVersions("01.10", "01.9") > 0)
        assertEquals(0, InstalledGameCandidateMerger.compareVersions("01.08", "1.8"))
        assertTrue(InstalledGameCandidateMerger.compareVersions(null, "01.00") < 0)
    }

    private fun game(
        titleId: String,
        version: String,
        category: String,
        installPath: String,
        iconPath: String? = null
    ) = InstalledPs3Game(
        titleId = titleId,
        title = "Test Game",
        contentId = null,
        saveDataId = titleId,
        version = version,
        category = category,
        iconPath = iconPath,
        catalogCoverUrl = null,
        installPath = installPath
    )

    private fun createParamSfoBytes(titleId: String, title: String, category: String? = null, contentId: String? = null): ByteArray {
        val entries = listOfNotNull(
            "TITLE_ID" to titleId,
            "TITLE" to title,
            category?.let { "CATEGORY" to it },
            contentId?.let { "CONTENT_ID" to it },
        )

        val headerSize = 20
        val entryTableSize = entries.size * 16
        val keyTableStart = headerSize + entryTableSize

        val keyBytes = mutableListOf<Byte>()
        val keyOffsets = mutableListOf<Int>()
        for ((key, _) in entries) {
            keyOffsets.add(keyBytes.size)
            keyBytes.addAll(key.toByteArray(Charsets.UTF_8).toList())
            keyBytes.add(0.toByte())
        }

        val dataTableStart = keyTableStart + keyBytes.size
        val dataBytes = mutableListOf<Byte>()
        val dataOffsets = mutableListOf<Int>()
        for ((_, value) in entries) {
            dataOffsets.add(dataBytes.size)
            dataBytes.addAll(value.toByteArray(Charsets.UTF_8).toList())
            dataBytes.add(0.toByte())
        }

        val totalSize = dataTableStart + dataBytes.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // Magic 0x46535000 (\0PSF)
        buffer.putInt(0x46535000)
        buffer.putShort(0x0101) // version
        buffer.putShort(0x0000) // reserved
        buffer.putInt(keyTableStart)
        buffer.putInt(dataTableStart)
        buffer.putInt(entries.size)

        for (i in entries.indices) {
            buffer.putShort(keyOffsets[i].toShort())
            buffer.put(0x04.toByte()) // UTF-8 format
            buffer.put(0x04.toByte())
            val valLen = entries[i].second.toByteArray(Charsets.UTF_8).size + 1
            buffer.putInt(valLen)
            buffer.putInt(valLen)
            buffer.putInt(dataOffsets[i])
        }

        buffer.put(keyBytes.toByteArray())
        buffer.put(dataBytes.toByteArray())

        return buffer.array()
    }
}
