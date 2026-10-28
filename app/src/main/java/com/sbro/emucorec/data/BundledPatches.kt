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
        BundledPatch("SPU-42bae8e5d6a9304068ba1c6bbfdc18d656e287a1", "Bink overlay skip", "BLES00928", "all"),
        BundledPatch("PPU-c4e26433d1eed9166eb0c67b6f66b2268f3704e2", "Freeze Fix", "BCES00052", "all"),
        BundledPatch("PPU-ec77eaf73a4f55d1c4ece532c3be6db0011e49ca", "Freeze Fix", "NPEA00452", "all"),
        BundledPatch("PPU-f67f3e99077ba256728ffa16800c75e006661158", "Freeze Fix", "BCUS98127", "01.00"),
        BundledPatch("PPU-c14042df6304d3e420a9917e6f8e5fc05cc38b4c", "Freeze Fix", "BCUS98127", "01.00"),
        BundledPatch("PPU-16506d9d5bf692d615645accd24bca1ee1f8f9a6", "Freeze Fix", "NPUA80965", "all"),
        BundledPatch("PPU-4c819c69904784a56685c31df12f5d492bc0ed64", "Freeze Fix", "BCAS20200", "01.03"),
        BundledPatch("PPU-4c819c69904784a56685c31df12f5d492bc0ed64", "Freeze Fix", "BCES01141", "01.03"),
        BundledPatch("PPU-4c819c69904784a56685c31df12f5d492bc0ed64", "Freeze Fix", "BCES01142", "01.03"),
        BundledPatch("PPU-4c819c69904784a56685c31df12f5d492bc0ed64", "Freeze Fix", "BCUS98175", "01.03"),
        BundledPatch("PPU-ed460e668a491f5e39f6547f3a24c2f20e2cb39b", "Freeze fix", "BCES01908", "01.00"),
        BundledPatch("PPU-ed460e668a491f5e39f6547f3a24c2f20e2cb39b", "Freeze fix", "BCES01949", "01.00"),
        BundledPatch("PPU-fadb0af6fb7bd0113e88fb9af3eb78fe3be05d08", "Freeze fix (all regions)", "BCUS99245", "01.01"),
        BundledPatch("PPU-fadb0af6fb7bd0113e88fb9af3eb78fe3be05d08", "Freeze fix (all regions)", "NPUA80908", "01.01"),
        BundledPatch("PPU-fadb0af6fb7bd0113e88fb9af3eb78fe3be05d08", "Freeze fix (all regions)", "BCES01908", "01.01"),
        BundledPatch("PPU-fadb0af6fb7bd0113e88fb9af3eb78fe3be05d08", "Freeze fix (all regions)", "BCES01949", "01.01"),
        BundledPatch("PPU-fadb0af6fb7bd0113e88fb9af3eb78fe3be05d08", "Freeze fix (all regions)", "NPEA00457", "01.01"),
        BundledPatch("PPU-f07f7086588a4ea86a28bd768f0cbe710f5b813b", "Freeze Fix", "BCAS20052", "all"),
        BundledPatch("PPU-f07f7086588a4ea86a28bd768f0cbe710f5b813b", "Freeze Fix", "BCES00301", "all"),
        BundledPatch("PPU-f07f7086588a4ea86a28bd768f0cbe710f5b813b", "Freeze Fix", "NPEA00088", "all"),
        BundledPatch("PPU-f07f7086588a4ea86a28bd768f0cbe710f5b813b", "Freeze Fix", "NPEA00106", "all"),
        BundledPatch("PPU-1d9e99e1f091cfbdf1714d04e690d9cd816e2971", "Freeze Fix", "NPUA80145", "all"),
        BundledPatch("PPU-0997e35d2b6738f5cecfda1d76380acca0828365", "Freeze Fix", "BCUS98124", "01.00"),
        BundledPatch("PPU-aa798f32a1fda1c23a20066edb1c623c486d53cc", "Disable MLAA", "BLUS30736", "01.00"),
        BundledPatch("PPU-56101fbdbac186fddef72207145015fd314a0be5", "ARMSX3 Junkyard crash fix", "BLUS30061", "01.00"),
        BundledPatch("PPU-30ce8c9f0a9552914275c90e2980749630f3ea18", "ARMSX3 intro movie crash fix", "BCES00112", "01.00"),
        BundledPatch("PPU-30ce8c9f0a9552914275c90e2980749630f3ea18", "ARMSX3 quickplay loading fix", "BCES00112", "01.00"),
    ) + listOf(
        "PPU-42367707f4caac2668f10cb46498f64bde9db440" to "BCUS99247",
        "PPU-4f1e9acd7d98961b4b742fb324a2faba6212ea67" to "BCUS98296",
        "PPU-6ac472e1f334f54ec4753cde9e8e1515bfab4d17" to "BCUS98296",
        "PPU-638ef307e2b35d593f599efec0dc0c37059a984b" to "BCES01893",
        "PPU-80f0d67b29d3d17ac885a309a3fc4cd8dcf50658" to "NPEA00502",
        "PPU-4213aba5ed17f7e3cd3f299fa44d9d9acef0ba18" to "NPUA81049",
        "PPU-6a7de7fdfd7e7cbd1031bf28a7104b29c308136c" to "NPHA80269",
        "PPU-43e618448eed82aa96856338d072f1e4978c36a8" to "NPJA00113",
    ).flatMap { (hash, serial) ->
        listOf("Disable MLAA", "Disable Motion Blur").map { BundledPatch(hash, it, serial, "01.22") }
    }

    fun enablePending(wasEnabled: (String) -> Boolean, enable: (BundledPatch) -> Boolean, markEnabled: (String) -> Unit) {
        for (patch in defaults) {
            if (!wasEnabled(patch.key) && enable(patch)) markEnabled(patch.key)
        }
    }
}

internal object BundledPatches {
    private const val REVISION = 4

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
