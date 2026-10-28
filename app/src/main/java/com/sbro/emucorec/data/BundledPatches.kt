package com.sbro.emucorec.data

import android.content.Context
import android.util.Log
import com.sbro.emucorec.core.Ps3Core
import java.io.File

internal data class BundledPatch(val hash: String, val name: String, val serial: String, val version: String) {
    val key: String get() = "$hash|$name|$serial|$version"
}

internal object BundledPatchInstaller {
    val defaults = listOf(
        BundledPatch("PPU-4b46d0161ca657ab16b0a779d9062810ea5ea2dd", "Graphics Fix", "BLUS30008", "01.01"),
        BundledPatch("PPU-6e828d3466bb1272243213bef0df0e499f3965a5", "Freeze fix", "BLUS30779", "01.00"),
        BundledPatch("PPU-db1e09abb1a2785532b89e9a366d2510ba8e070c", "Battle freeze fix", "BLUS31519", "01.00"),
        BundledPatch("PPU-3e9032a3f7857057ef783810bd8f34a439baaa59", "Field at 30 fps with a 60 fps limit", "BLUS30903", "all"),
    )

    fun enablePending(wasEnabled: (String) -> Boolean, enable: (BundledPatch) -> Boolean, markEnabled: (String) -> Unit) {
        for (patch in defaults) {
            if (!wasEnabled(patch.key) && enable(patch)) markEnabled(patch.key)
        }
    }
}

internal object BundledPatches {
    private const val REVISION = 1

    fun ensureInstalled(context: Context, root: String) {
        // Each storage root has its own patch database and its own installation state.
        val prefs = context.getSharedPreferences("bundled_patches", Context.MODE_PRIVATE)
        val prefix = "$root|"
        val revisionKey = prefix + "revision"
        val database = File(root, "config/patches/patch.yml")
        try {
            if (prefs.getInt(revisionKey, 0) < REVISION || !database.isFile) {
                val yaml = context.assets.open("canary_patches.yml").bufferedReader().use { it.readText() }
                check(Ps3Core.instance.patchesImport(yaml) >= 0) { "Failed to import bundled patches" }
                check(prefs.edit().putInt(revisionKey, REVISION).commit()) { "Failed to save bundled patch revision" }
            }
            BundledPatchInstaller.enablePending(
                wasEnabled = { prefs.getBoolean(prefix + it, false) },
                enable = { Ps3Core.instance.patchSetEnabled(it.hash, it.name, it.serial, it.version, true) },
                markEnabled = { check(prefs.edit().putBoolean(prefix + it, true).commit()) },
            )
        } catch (e: Exception) {
            Log.e("BundledPatches", "Could not install bundled patches; will retry", e)
        }
    }
}
