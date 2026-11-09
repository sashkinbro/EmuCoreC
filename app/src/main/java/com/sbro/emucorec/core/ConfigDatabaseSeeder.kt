package com.sbro.emucorec.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Seeds the upstream RPCS3 per-title configuration database.
 *
 * Desktop RPCS3 downloads `https://api.rpcs3.net/config/?api=v1` and applies
 * per-title recommended settings automatically. The core on Android reads the
 * same data from `<ps3>/config/config_db/<TITLE_ID>.yml` (see
 * `get_database_config` in `android/src/rpcsx-android.cpp`), but nothing
 * populates that directory, so titles that are broken under the generic
 * defaults (for example Assassin's Creed IV: Black Flag, which needs the PPU
 * static interpreter) would silently boot misconfigured.
 *
 * This seeder splits the snapshot bundled in assets (`rpcs3_config_db.json`,
 * refreshable with `./gradlew :app:updateConfigDb`) into that layout on first
 * run and whenever the bundled snapshot becomes newer. Failures are
 * non-fatal: the emulator simply boots without database configs.
 */
object ConfigDatabaseSeeder {
    private const val TAG = "EmuCoreC-ConfigDb"
    private const val ASSET_NAME = "rpcs3_config_db.json"
    private const val VERSION_NAME = ".version"
    private const val LOCAL_REVISION = 2

    private val coreOverrides = buildMap {
        listOf("BLUS31202", "BLES01885", "BLES01886", "BLUS30778", "BLES01329", "BLES01330")
            .forEach { put(it, mapOf("Accurate SPU Reservations" to "false")) }
        listOf("BLUS30490", "BLES00875", "BLJM60180", "BLUD80019")
            .forEach { put(it, mapOf("PPU Event Wake-Up Delay" to "35")) }
        listOf("BLUS30612", "BLES01236", "NPEB00813", "NPEB90337", "NPEB90343", "NPHB00369", "NPHB00446", "NPUB90567", "NPUB90604")
            .forEach { put(it, mapOf("MFC Commands Shuffling Limit" to "1")) }
        listOf("BLUS30203", "BLES00397").forEach {
            put(it, mapOf("SPU MMIO Delay Enabled" to "true", "SPU MMIO Delay" to "300", "Pause Game During SPU Compilation" to "true"))
        }
        put("BLUS30218", mapOf("Accurate SPU Reservations" to "false"))
        listOf("NPUA80930", "NPUB31590").forEach { put(it, mapOf("PPU Decoder" to "Recompiler (LLVM)")) }
        listOf("BCUS98234", "BCES01007").forEach { put(it, mapOf("Preferred SPU Threads" to "0")) }
        listOf("BLUS30833", "BLES01395").forEach { put(it, mapOf("SPU Cache" to "false")) }
        listOf("BLUS30682", "BLUS30127", "NPUB30702").forEach { put(it, mapOf("SPU XFloat Accuracy" to "Approximate")) }
        // ARMSX3 757ba16744 / 4f088e3ad9: these games dispatch many small SPU routines.
        listOf("NPUB30828", "NPEB01054").forEach { put(it, mapOf("SPU Block Size" to "Mega")) }
        listOf("MRTC00003", "BLUS30416", "BLES00783", "BLJM67005", "BCAS25005").forEach {
            put(it, mapOf("SPU Block Size" to "Mega", "Stub PPU Traps" to "20"))
        }
        // ARMSX3 3f1212fdc3: Relaxed fixes God of War III's cutscene rendering.
        listOf("BCUS98111", "BCES00510", "BCES00799", "BCAS25003", "BCKS15003", "BCJS37001", "NPUA70080")
            .forEach { put(it, mapOf("SPU XFloat Accuracy" to "Relaxed")) }
        // ARMSX3 de6a59feab, contributed by k1r4ct: free CPU time for the PPU in combat.
        listOf(
            "BCES00052", "BCUS98127", "BCJS30014", "NPEA00452", "NPUA80965",
            "BCES00301", "BCAS20052", "NPEA00088", "NPUA80145", "NPEA00106", "NPJA00033",
            "BCES01908", "BCES01949", "BCUS99245", "NPEA00457", "NPUA80908",
        ).forEach {
            put(it, mapOf("Thread Scheduler Mode" to "RPCS3 Scheduler", "SPU GETLLAR Busy Waiting Percentage" to "0"))
        }
        // ARMSX3 GameDefaults, expanded in 4f088e3ad9. Keep these in the per-title database
        // rather than writing a workaround into global config.yml where the next game inherits it.
        listOf(
            "BCUS98233", "BCES01175", "BCES01176", "BCJS37004", "BCAS25009", "BCUS99086",
            "BCES01670", "BCES01692", "BCAS25014", "NPUA80858", "NPEA00422",
            "BCUS98232", "BCES01741", "BCES01742", "BCAS25016", "BCJS37008", "NPUA80918", "NPEA00445", "NPHA80258",
        ).forEach { put(it, mapOf("Stub PPU Traps" to "1")) }
        listOf("BLUS30826", "NPUB31509").forEach { put(it, mapOf("RSX FIFO Fetch Accuracy" to "Ordered & Atomic")) }
    }
    private val videoOverrides = buildMap {
        listOf("BLUS31213", "BLES01935").forEach { put(it, mapOf("Frame limit" to "PS3 Native")) }
        put("BLUS30903", mapOf("Frame limit" to "60"))
        listOf("BLUS31519", "BLES02136", "BLJS10257").forEach { put(it, mapOf("Shader Precision" to "High")) }
        listOf("BCUS98234", "BCES01007").forEach { put(it, mapOf("Write Color Buffers" to "false")) }
        // ARMSX3 65454fef8e / 5a34aeb06f: Yakuza: Dead Souls FIFO stalls/desync.
        listOf("BLUS30826", "NPUB31509").forEach {
            put(it, mapOf("Disable FIFO Reordering" to "true", "Driver Wake-Up Delay" to "20"))
        }
    }
    private val vulkanOverrides = buildMap {
        listOf("BLUS30203", "BLES00397").forEach { put(it, mapOf("VRAM allocation limit (MB)" to "3072")) }
        // ARMSX3 b7bc42b674 / 8aac056e15 (k1r4ct): Tools of Destruction only.
        listOf("BCES00052", "BCUS98127", "BCJS30014", "NPEA00452", "NPUA80965").forEach {
            put(it, mapOf("Vertex Fetch Specialization" to "true", "Early Submit Draw Threshold" to "1024"))
        }
    }

    /** Guards against pathological entries; real configs are a few hundred bytes. */
    private const val MAX_CONFIG_BYTES = 8192
    private val TITLE_ID_PATTERN = Regex("^[A-Z0-9]{9}$")

    fun ensureSeeded(context: Context) {
        runCatching { seed(context) }.onFailure { error ->
            Log.w(TAG, "Config database seeding failed, continuing without it", error)
        }
    }

    private fun seed(context: Context) {
        val raw = runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: run {
            Log.w(TAG, "Bundled config database is not valid JSON")
            return
        }
        if (root.optInt("return_code", -255) != 0) {
            Log.w(TAG, "Bundled config database has bad return_code")
            return
        }
        val bundledTimestamp = root.optLong("timestamp", 0L).takeIf { it > 0 } ?: return
        val targetDir = File(EmulatorStorage.ps3Root(context), "config/config_db")

        val version = "$bundledTimestamp:$LOCAL_REVISION"
        if (readSeededVersion(targetDir) == version && hasEntries(targetDir)) {
            return
        }

        val games = root.optJSONObject("games") ?: return
        // Wipe first so titles removed upstream cannot linger with stale configs.
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        var written = 0
        val titleIds = games.keys().asSequence().toSet() + coreOverrides.keys + videoOverrides.keys + vulkanOverrides.keys
        titleIds.forEach { titleId ->
            if (!TITLE_ID_PATTERN.matches(titleId)) return@forEach
            val original = games.optJSONObject(titleId)?.optString("config").orEmpty()
            val config = prepareConfig(titleId, original)
            if (config.isBlank() || config.toByteArray().size > MAX_CONFIG_BYTES) return@forEach
            runCatching {
                File(targetDir, "$titleId.yml").writeText(config)
                written++
            }
        }
        File(targetDir, VERSION_NAME).writeText(version)
        Log.i(TAG, "Seeded $written per-title configs (db timestamp $bundledTimestamp)")
    }

    private fun hasEntries(dir: File): Boolean =
        dir.listFiles { file -> file.isFile && file.name.endsWith(".yml") }?.isNotEmpty() == true

    private fun readSeededVersion(dir: File): String? =
        runCatching { File(dir, VERSION_NAME).readText().trim() }.getOrNull()

    internal fun prepareConfig(titleId: String, original: String): String {
        // Match ARMSX3's evidenced Android exceptions: desktop SPURS caps starve the
        // job chain, and Off/Infinite frame limits waste power or run games too fast.
        // PS3 Native has its own pacing and must remain; Multithreaded RSX is supported.
        val mobile = original.lineSequence().filterNot { line ->
            val name = line.substringBefore(':').trim().removePrefix("- ")
            name == "Max SPURS Threads" ||
                (name == "Frame limit" && line.substringAfter(':').trim() in setOf("Off", "Infinite"))
        }.joinToString("\n")
        val core = applyCoreOverrides(mobile, coreOverrides[titleId].orEmpty())
        val video = applySectionOverrides(core, "Video", videoOverrides[titleId].orEmpty())
        return applyVulkanOverrides(video, vulkanOverrides[titleId].orEmpty())
    }
}

internal fun applyCoreOverrides(config: String, overrides: Map<String, String>): String =
    applySectionOverrides(config, "Core", overrides)

internal fun applySectionOverrides(config: String, name: String, overrides: Map<String, String>): String {
    if (overrides.isEmpty()) return config
    var result = config.trimEnd()
    val header = Regex("(?m)^${Regex.escape(name)}:[ \\t]*$")
    if (!header.containsMatchIn(result)) {
        result = listOf(result, "$name:").filter(String::isNotEmpty).joinToString("\n")
    }
    val sectionStart = header.find(result)!!.range.first
    val nextSection = Regex("(?m)^[^ \\t\\n][^\\n]*:").find(result, sectionStart + 1)?.range?.first ?: result.length
    var section = result.substring(sectionStart, nextSection).trimEnd()
    overrides.forEach { (key, value) ->
        val existing = Regex("(?m)^  ${Regex.escape(key)}:[^\\n]*$")
        section = if (existing.containsMatchIn(section)) {
            section.replace(existing, "  $key: $value")
        } else {
            section.replaceFirst(header, "$name:\n  $key: $value")
        }
    }
    val suffix = result.substring(nextSection)
    return result.substring(0, sectionStart) + section + (if (suffix.isEmpty()) "\n" else "\n$suffix\n")
}

internal fun applyVulkanOverrides(config: String, overrides: Map<String, String>): String {
    if (overrides.isEmpty()) return config
    var result = config.trimEnd()
    val video = Regex("(?m)^Video:[ \\t]*$")
    if (!video.containsMatchIn(result)) result = listOf(result, "Video:").filter(String::isNotEmpty).joinToString("\n")
    val videoStart = video.find(result)!!.range.first
    val videoEnd = Regex("(?m)^[^ \\t\\n][^\\n]*:").find(result, videoStart + 1)?.range?.first ?: result.length
    var section = result.substring(videoStart, videoEnd).trimEnd()
    val vulkan = Regex("(?m)^  Vulkan:[ \\t]*$")
    if (!vulkan.containsMatchIn(section)) section = section.replaceFirst(video, "Video:\n  Vulkan:")
    val start = vulkan.find(section)!!.range.first
    val end = Regex("(?m)^  [^ \\t\\n][^\\n]*:").find(section, start + 1)?.range?.first ?: section.length
    var block = section.substring(start, end).trimEnd()
    overrides.forEach { (key, value) ->
        val existing = Regex("(?m)^    ${Regex.escape(key)}:[^\\n]*$")
        block = if (existing.containsMatchIn(block)) block.replace(existing, "    $key: $value")
        else block.replaceFirst(vulkan, "  Vulkan:\n    $key: $value")
    }
    val afterBlock = section.substring(end)
    section = section.substring(0, start) + block + if (afterBlock.isEmpty()) "" else "\n$afterBlock"
    val suffix = result.substring(videoEnd)
    return result.substring(0, videoStart) + section + (if (suffix.isEmpty()) "\n" else "\n$suffix\n")
}
