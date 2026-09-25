package com.sbro.emucorec.data

import android.content.Context
import android.util.Xml
import com.sbro.emucorec.core.EmulatorStorage
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.RandomAccessFile
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class Ps3TrophyGrade {
    Platinum,
    Gold,
    Silver,
    Bronze,
    Unknown
}

data class Ps3Trophy(
    val id: Int,
    val groupId: Int,
    val grade: Ps3TrophyGrade,
    val hidden: Boolean,
    val name: String,
    val detail: String,
    val unlocked: Boolean,
    val unlockedAtEpochSeconds: Long?,
    val iconPath: String?
)

data class Ps3TrophyGroup(
    val id: Int,
    val name: String,
    val detail: String,
    val trophies: List<Ps3Trophy>
)

data class Ps3TrophySet(
    val communicationId: String,
    val titleId: String?,
    val gameTitle: String,
    val gameIconPath: String?,
    val coverUrl: String? = null,
    val setName: String,
    val setDetail: String,
    val groups: List<Ps3TrophyGroup>
) {
    val trophies: List<Ps3Trophy> = groups.flatMap { it.trophies }
    val trophyCount: Int = trophies.size
    val unlockedCount: Int = trophies.count { it.unlocked }
}

class TrophyRepository {
    fun list(context: Context): List<Ps3TrophySet> {
        val installedGames = InstalledGameRepository().loadInstalledGames(context)
        val installedPackages = installedGames.flatMap { game ->
            game.trophyPackages(context).mapNotNull { packageDir ->
                val trp = File(packageDir, TROPHY_TRP_NAME).takeIf { it.isFile } ?: return@mapNotNull null
                val commId = packageDir.name.takeIf { it.isLikelyTrophyCommunicationId() }
                    ?: TrpArchive(trp).readTextEntry(TROPCONF_NAME)?.let(::parseCommunicationId)
                    ?: TrpArchive(trp).readTextEntry("TROP.SFM")?.let(::parseCommunicationId)
                    ?: return@mapNotNull null
                InstalledTrophyPackage(commId, packageDir, game)
            }
        }
        val installedByCommId = installedPackages.associateBy { it.communicationId }

        val commIds = linkedSetOf<String>()
        installedByCommId.keys.forEach(commIds::add)
        trophyRoots(context).forEach { root ->
            root.listFiles().orEmpty()
                .filter(File::isDirectory)
                .mapTo(commIds) { it.name }
        }

        return commIds.mapNotNull { commId ->
            val installedPackage = installedByCommId[commId]
            loadSet(context, commId, installedPackage, installedGames)
        }.sortedWith(
            compareByDescending<Ps3TrophySet> { it.unlockedCount > 0 }
                .thenBy { it.gameTitle.lowercase() }
                .thenBy { it.communicationId.lowercase() }
        )
    }

    fun loadForTitle(context: Context, titleId: String): List<Ps3TrophySet> {
        val normalized = titleId.lowercase()
        val game = InstalledGameRepository().findByTitleId(context, titleId)
        val gameTitleNorm = game?.title?.lowercase()
        return list(context).filter {
            it.titleId?.lowercase() == normalized ||
                it.communicationId.lowercase() == normalized ||
                it.gameTitle.lowercase() == normalized ||
                (gameTitleNorm != null && (it.gameTitle.lowercase() == gameTitleNorm || it.setName.lowercase() == gameTitleNorm))
        }
    }

    private fun loadSet(
        context: Context,
        commId: String,
        installedPackage: InstalledTrophyPackage?,
        installedGames: List<InstalledPs3Game> = emptyList()
    ): Ps3TrophySet? {
        val confDir = trophyRoots(context)
            .map { File(it, commId) }
            .firstOrNull { it.isDirectory }
        val trpFile = installedPackage
            ?.directory
            ?.let { File(it, TROPHY_TRP_NAME) }
            ?.takeIf { it.isFile }

        val trp = trpFile?.let { runCatching { TrpArchive(it) }.getOrNull() }
        val configXml = readText(confDir?.resolve("TROPCONF.SFM"))
            ?: trp?.readTextEntry("TROPCONF.SFM")
            ?: readText(confDir?.resolve("TROP.SFM"))
            ?: trp?.readTextEntry("TROP.SFM")
            ?: readText(confDir?.resolve("TROP_00.SFM"))
            ?: trp?.readTextEntry("TROP_00.SFM")
            ?: trp?.entryNames()
                ?.firstOrNull { it.startsWith("TROP", ignoreCase = true) && it.endsWith(".SFM", ignoreCase = true) }
                ?.let(trp::readTextEntry)
            ?: return null
        val detailXml = preferredDetailXml(confDir, trp) ?: configXml
        val progress = findProgressFile(context, commId)
            ?.let { runCatching { TrophyProgress.read(it) }.getOrNull() }
            ?: TrophyProgress.Empty

        val config = parseConfigXml(configXml)
        if (config.trophies.isEmpty()) return null
        val details = parseDetailXml(detailXml)
        val iconDir = confDir ?: extractIcons(context, commId, trp)

        val trophies = config.trophies.map { item ->
            val trophyDetail = details.trophies[item.id]
            val hiddenLocked = item.hidden && !progress.isUnlocked(item.id)
            Ps3Trophy(
                id = item.id,
                groupId = item.groupId,
                grade = progress.grade(item.id) ?: item.grade,
                hidden = item.hidden,
                name = if (hiddenLocked) "" else trophyDetail?.name.orEmpty(),
                detail = if (hiddenLocked) "" else trophyDetail?.detail.orEmpty(),
                unlocked = progress.isUnlocked(item.id),
                unlockedAtEpochSeconds = progress.unlockedAt(item.id),
                iconPath = iconDir?.resolve("TROP${item.id.toString().padStart(3, '0')}.PNG")
                    ?.takeIf { it.isFile }
                    ?.absolutePath
            )
        }.sortedBy { it.id }

        val groups = trophies.groupBy { it.groupId }
            .map { (groupId, groupTrophies) ->
                val detail = details.groups[groupId]
                Ps3TrophyGroup(
                    id = groupId,
                    name = detail?.name?.takeIf(String::isNotBlank)
                        ?: if (groupId == 0) details.setName else "Group $groupId",
                    detail = detail?.detail.orEmpty(),
                    trophies = groupTrophies
                )
            }
            .sortedBy { it.id }

        val game = installedPackage?.game ?: installedGames.firstOrNull { g ->
            g.titleId.equals(commId, ignoreCase = true) ||
                (details.setName.isNotBlank() && g.title.equals(details.setName, ignoreCase = true)) ||
                (details.setName.isNotBlank() && (g.title.contains(details.setName, ignoreCase = true) || details.setName.contains(g.title, ignoreCase = true))) ||
                g.trophyPackages(context).any { it.name.equals(commId, ignoreCase = true) }
        }

        val fallbackName = game?.title ?: details.setName.takeIf(String::isNotBlank) ?: commId
        val fallbackIcon = game?.iconPath
            ?: confDir?.resolve("ICON0.PNG")?.takeIf(File::isFile)?.absolutePath
            ?: confDir?.resolve("GR00.PNG")?.takeIf(File::isFile)?.absolutePath
            ?: confDir?.resolve("TROP000.PNG")?.takeIf(File::isFile)?.absolutePath

        return Ps3TrophySet(
            communicationId = commId,
            titleId = game?.titleId,
            gameTitle = fallbackName,
            gameIconPath = fallbackIcon,
            coverUrl = game?.catalogCoverUrl,
            setName = details.setName.takeIf(String::isNotBlank) ?: fallbackName,
            setDetail = details.setDetail,
            groups = groups
        )
    }

    /** RPCS3 stores each trophy set under dev_hdd0/home/<user>/trophy/<NPWR...>. */
    private fun trophyRoots(context: Context): List<File> {
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
                    val trophyDir = File(userDir, "trophy")
                    if (trophyDir.isDirectory) roots.add(trophyDir)
                }
            }
        }
        return roots.distinctBy { it.absolutePath }
    }

    private fun findProgressFile(context: Context, commId: String): File? {
        return trophyRoots(context)
            .map { File(File(it, commId), "TROPUSR.DAT") }
            .firstOrNull { it.isFile }
    }

    private fun preferredDetailXml(confDir: File?, trp: TrpArchive?): String? {
        val confFiles = confDir?.listFiles().orEmpty()
        val preferredFile = confFiles.firstOrNull { it.name.equals("TROP.SFM", ignoreCase = true) }
            ?: confFiles.firstOrNull { it.name.equals("TROP_00.SFM", ignoreCase = true) }
            ?: confFiles.firstOrNull { it.name.startsWith("TROP_", ignoreCase = true) && it.extension.equals("SFM", ignoreCase = true) }
        if (preferredFile != null) return readText(preferredFile)

        return trp?.readTextEntry("TROP.SFM")
            ?: trp?.readTextEntry("TROP_00.SFM")
            ?: trp?.entryNames()
                ?.firstOrNull { it.startsWith("TROP", ignoreCase = true) && it.endsWith(".SFM", ignoreCase = true) }
                ?.let(trp::readTextEntry)
    }

    private fun extractIcons(context: Context, commId: String, trp: TrpArchive?): File? {
        trp ?: return null
        val outputDir = File(EmulatorStorage.cacheRoot(context), "trophies/$commId").apply { mkdirs() }
        trp.entryNames()
            .filter { it.startsWith("TROP", ignoreCase = true) && it.endsWith(".PNG", ignoreCase = true) }
            .forEach { name ->
                val target = File(outputDir, name.uppercase())
                if (!target.isFile) {
                    runCatching { target.writeBytes(trp.readEntry(name)) }
                }
            }
        return outputDir.takeIf { it.listFiles().orEmpty().any(File::isFile) }
    }

    private fun InstalledPs3Game.trophyPackages(context: Context): List<File> {
        val root = File(installPath)
        if (root.isFile && com.sbro.emucorec.core.Ps3IsoParser.isIsoImage(root)) {
            return com.sbro.emucorec.core.Ps3IsoParser.extractTrophyPackages(context, root)
        }
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .maxDepth(6)
            .filter { it.isFile && (it.name.equals(TROPHY_TRP_NAME, ignoreCase = true) || it.name.endsWith(".TRP", ignoreCase = true)) }
            .mapNotNull { it.parentFile }
            .distinctBy { it.absolutePath }
            .toList()
    }

    private fun readText(file: File?): String? {
        return file?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }

    private fun parseCommunicationId(xml: String): String? {
        var result: String? = null
        parseXml(xml) { parser, event ->
            if (event == XmlPullParser.START_TAG && parser.name.equals("trophyconf", ignoreCase = true)) {
                result = (parser.attr("npcommid").takeIf(String::isNotBlank)
                    ?: parser.attr("NPCOMMID").takeIf(String::isNotBlank))
                    ?.takeIf { it.isLikelyTrophyCommunicationId() }
            }
        }
        if (result == null) {
            val match = Regex("""npcommid=["'](NPWR\d+_\d+)["']""", RegexOption.IGNORE_CASE).find(xml)
            result = match?.groupValues?.getOrNull(1)
        }
        return result
    }

    private fun String.isLikelyTrophyCommunicationId(): Boolean =
        startsWith("NPWR", ignoreCase = true) && contains("_")

    private fun parseConfigXml(xml: String): TrophyConfig {
        val trophies = mutableListOf<ConfigTrophy>()
        parseXml(xml) { parser, event ->
            if (event == XmlPullParser.START_TAG && parser.name.equals("trophy", ignoreCase = true)) {
                val id = parser.attrInt("id") ?: return@parseXml
                val hiddenAttr = parser.attr("hidden").lowercase()
                val isHidden = hiddenAttr == "yes" || hiddenAttr == "y" || hiddenAttr == "true" || hiddenAttr == "1"
                trophies += ConfigTrophy(
                    id = id,
                    groupId = parser.attrInt("gid") ?: 0,
                    grade = parser.attr("ttype").toGrade(),
                    hidden = isHidden
                )
            }
        }
        return TrophyConfig(trophies)
    }

    private fun parseDetailXml(xml: String): TrophyDetails {
        val trophies = mutableMapOf<Int, TextPair>()
        val groups = mutableMapOf<Int, TextPair>()
        var setName = ""
        var setDetail = ""
        var trophyId: Int? = null
        var groupId: Int? = null
        var activeTextTag = ""
        var name = ""
        var detail = ""

        parseXml(xml) { parser, event ->
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name?.lowercase()) {
                    "trophy" -> {
                        trophyId = parser.attrInt("id")
                        name = ""
                        detail = ""
                    }
                    "group" -> {
                        groupId = parser.attrInt("id")
                        name = ""
                        detail = ""
                    }
                    "title-name", "title-detail", "name", "detail" -> activeTextTag = parser.name?.lowercase().orEmpty()
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text.orEmpty()
                    if (text.isNotEmpty()) {
                        when (activeTextTag) {
                            "title-name" -> setName += text
                            "title-detail" -> setDetail += text
                            "name" -> name += text
                            "detail" -> detail += text
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name?.lowercase()) {
                    "trophy" -> {
                        trophyId?.let { trophies[it] = TextPair(name.trim(), detail.trim()) }
                        trophyId = null
                        name = ""
                        detail = ""
                    }
                    "group" -> {
                        groupId?.let { groups[it] = TextPair(name.trim(), detail.trim()) }
                        groupId = null
                        name = ""
                        detail = ""
                    }
                    "title-name", "title-detail", "name", "detail" -> activeTextTag = ""
                }
            }
        }
        return TrophyDetails(setName.trim(), setDetail.trim(), groups, trophies)
    }

    private fun parseXml(xml: String, onEvent: (XmlPullParser, Int) -> Unit) {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            onEvent(parser, event)
            event = parser.next()
        }
    }

    private fun XmlPullParser.attr(name: String): String =
        getAttributeValue(null, name).orEmpty()

    private fun XmlPullParser.attrInt(name: String): Int? =
        attr(name).toIntOrNull()

    private fun String.toGrade(): Ps3TrophyGrade {
        return when (uppercase()) {
            "P" -> Ps3TrophyGrade.Platinum
            "G" -> Ps3TrophyGrade.Gold
            "S" -> Ps3TrophyGrade.Silver
            "B" -> Ps3TrophyGrade.Bronze
            else -> Ps3TrophyGrade.Unknown
        }
    }

    private data class ConfigTrophy(
        val id: Int,
        val groupId: Int,
        val grade: Ps3TrophyGrade,
        val hidden: Boolean
    )

    private data class TrophyConfig(val trophies: List<ConfigTrophy>)
    private data class TextPair(val name: String, val detail: String)
    private data class InstalledTrophyPackage(
        val communicationId: String,
        val directory: File,
        val game: InstalledPs3Game
    )
    private data class TrophyDetails(
        val setName: String,
        val setDetail: String,
        val groups: Map<Int, TextPair>,
        val trophies: Map<Int, TextPair>
    )

    private class TrpArchive(private val file: File) {
        private val entries: List<TrpEntry>

        init {
            val list = mutableListOf<TrpEntry>()
            runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val magic = raf.readInt()
                    val isBe = (magic == 0x004DA2DC || magic == 0xDCA24D00.toInt())
                    val isLe = (Integer.reverseBytes(magic) == 0x004DA2DC || Integer.reverseBytes(magic) == 0xDCA24D00.toInt())
                    if (!isBe && !isLe) error("Invalid TRP magic: 0x${Integer.toHexString(magic)}")

                    raf.seek(0x10)
                    val entryCount = if (isBe) raf.readInt() else Integer.reverseBytes(raf.readInt())
                    val entryInfoOffset = if (isBe) raf.readInt() else Integer.reverseBytes(raf.readInt())
                    val baseOffset = if (entryInfoOffset > 0) entryInfoOffset else 0x40

                    for (index in 0 until entryCount.coerceIn(0, 500)) {
                        raf.seek((baseOffset + index * ENTRY_SIZE).toLong())
                        val filenameBytes = ByteArray(FILENAME_SIZE)
                        raf.readFully(filenameBytes)
                        val filename = filenameBytes
                            .takeWhile { it.toInt() != 0 }
                            .toByteArray()
                            .toString(Charsets.UTF_8)
                            .trim()

                        raf.seek((baseOffset + index * ENTRY_SIZE + 0x20).toLong())
                        val offset = if (isBe) raf.readLong() else java.lang.Long.reverseBytes(raf.readLong())
                        val size = if (isBe) raf.readLong() else java.lang.Long.reverseBytes(raf.readLong())
                        if (filename.isNotEmpty() && size > 0 && offset > 0) {
                            list.add(TrpEntry(filename, offset, size))
                        }
                    }
                }
            }
            entries = list
        }

        fun entryNames(): List<String> = entries.map { it.filename }

        fun readTextEntry(name: String): String? =
            runCatching { readEntry(name).toString(Charsets.UTF_8) }.getOrNull()

        fun readEntry(name: String): ByteArray {
            val entry = entries.firstOrNull { it.filename.equals(name, ignoreCase = true) }
                ?: error("Missing TRP entry $name")
            require(entry.size <= Int.MAX_VALUE)
            return RandomAccessFile(file, "r").use { raf ->
                raf.seek(entry.offset)
                ByteArray(entry.size.toInt()).also(raf::readFully)
            }
        }

        private data class TrpEntry(val filename: String, val offset: Long, val size: Long)

        private companion object {
            const val TRP_MAGIC = 0x004DA2DC
            const val ENTRY_SIZE = 0x40
            const val FILENAME_SIZE = 0x20
        }
    }

    private class TrophyProgress(
        private val unlockedIds: Set<Int>,
        private val timestamps: Map<Int, Long>,
        private val grades: Map<Int, Ps3TrophyGrade>
    ) {
        fun isUnlocked(id: Int): Boolean = id in unlockedIds
        fun unlockedAt(id: Int): Long? = timestamps[id]?.takeIf { it > 0L }
        fun grade(id: Int): Ps3TrophyGrade? = grades[id]?.takeIf { it != Ps3TrophyGrade.Unknown }

        companion object {
            val Empty = TrophyProgress(emptySet(), emptyMap(), emptyMap())

            fun read(file: File): TrophyProgress {
                val bytes = file.readBytes()
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                if (buffer.remaining() < 4 || buffer.int != TROPHY_USR_MAGIC) return Empty

                val progressFlags = IntArray(FLAG_WORDS) { buffer.nextIntOrZero() }
                repeat(FLAG_WORDS) { buffer.nextIntOrZero() }
                buffer.nextIntOrZero()
                buffer.nextIntOrZero()
                buffer.nextIntOrZero()
                repeat(MAX_GROUPS) { buffer.nextIntOrZero() }

                val timestamps = if (buffer.remaining() >= MAX_TROPHIES * Long.SIZE_BYTES) {
                    LongArray(MAX_TROPHIES) { buffer.nextLongOrZero() }
                } else {
                    LongArray(MAX_TROPHIES)
                }
                val grades = if (buffer.remaining() >= MAX_TROPHIES * Int.SIZE_BYTES) {
                    IntArray(MAX_TROPHIES) { buffer.nextIntOrZero() }
                } else {
                    IntArray(MAX_TROPHIES)
                }

                val unlocked = (0 until MAX_TROPHIES)
                    .filter { id -> progressFlags[id / 32] and (1 shl (id % 32)) != 0 }
                    .toSet()
                return TrophyProgress(
                    unlockedIds = unlocked,
                    timestamps = unlocked.associateWith { timestamps[it] },
                    grades = buildMap {
                        grades.forEachIndexed { index, value ->
                            val grade = when (value) {
                                1 -> Ps3TrophyGrade.Platinum
                                2 -> Ps3TrophyGrade.Gold
                                3 -> Ps3TrophyGrade.Silver
                                4 -> Ps3TrophyGrade.Bronze
                                else -> Ps3TrophyGrade.Unknown
                            }
                            if (grade != Ps3TrophyGrade.Unknown) {
                                put(index, grade)
                            }
                        }
                    }
                )
            }

            private fun ByteBuffer.nextIntOrZero(): Int =
                if (remaining() >= Int.SIZE_BYTES) int else 0

            private fun ByteBuffer.nextLongOrZero(): Long =
                if (remaining() >= Long.SIZE_BYTES) long else 0L

            private const val TROPHY_USR_MAGIC = 0x12D5819A
            private const val MAX_TROPHIES = 128
            private const val FLAG_WORDS = MAX_TROPHIES / 32
            private const val MAX_GROUPS = 16
        }
    }

    private companion object {
        const val TROPHY_TRP_NAME = "TROPHY.TRP"
        const val TROPCONF_NAME = "TROPCONF.SFM"
    }
}
