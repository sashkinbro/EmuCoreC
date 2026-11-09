package com.sbro.emucorec.data

import com.sbro.emucorec.core.Ps3SfoParser
import java.io.File

/** Reads fresh folder metadata; the optional native resolver verifies C00 unlock licences. */
internal fun readInstalledFolder(
    directory: File,
    trialMetadataRoot: (File) -> File? = { null },
): InstalledPs3Game? {
    val root = listOf(directory, File(directory, "PS3_GAME"), File(directory, "USRDIR"))
        .firstOrNull { File(it, "PARAM.SFO").isFile } ?: return null
    val base = Ps3SfoParser.parse(File(root, "PARAM.SFO"))
    val titleId = base.titleId?.takeIf(String::isNotBlank) ?: return null
    var metadata = base
    var metadataRoot = root
    if (root == directory && base.category == "HG" && !base.contentId.isNullOrBlank() &&
        File(directory, "C00/PARAM.SFO").isFile
    ) {
        // Ask on every scan: installing/removing the unlock key need not touch PARAM.SFO.
        val verified = runCatching { trialMetadataRoot(directory)?.canonicalFile }.getOrNull()
        if (verified == File(directory, "C00").canonicalFile) {
            val full = Ps3SfoParser.parse(File(verified, "PARAM.SFO"))
            if (full.titleId == titleId && !full.title.isNullOrBlank()) {
                metadata = full
                metadataRoot = verified
            }
        }
    }
    val title = metadata.title?.takeIf(String::isNotBlank) ?: return null
    val icon = File(metadataRoot, "ICON0.PNG").takeIf(File::isFile) ?: File(root, "ICON0.PNG").takeIf(File::isFile)
    return InstalledPs3Game(
        titleId, title, metadata.contentId ?: base.contentId, metadata.saveDataId ?: titleId, metadata.version, metadata.category,
        icon?.absolutePath, null, directory.absolutePath,
    )
}
