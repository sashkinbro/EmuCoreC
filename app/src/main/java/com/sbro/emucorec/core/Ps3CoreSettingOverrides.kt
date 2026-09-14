package com.sbro.emucorec.core

import android.content.Context
import android.util.Log
import net.rpcsx.RPCSX
import org.json.JSONObject

/**
 * Durable global and per-title overrides for settings exposed by the RPCS3 core.
 *
 * RPCS3 keeps one live config tree on Android. A per-game edit therefore has
 * to be reset to its global value before the next title boots. This store remembers
 * both tiers and replays them in the correct order: global first, selected title last.
 */
object Ps3CoreSettingOverrides {
    private const val PREFS = "emucorec_ps3_core_overrides"
    private const val KEY_GLOBAL = "global"
    private const val KEY_BASELINE = "baseline"
    private const val GAME_PREFIX = "game."
    private const val TAG = "EmuCoreC-CoreConfig"

    /**
     * Recommended performance defaults for ARM64 Android hardware.
     *
     * "Max LLVM Compile Threads = 2": the core default is 2 as well (auto = 0
     * would use every core and heat the phone up badly during first-boot PPU
     * compilation). Applied on every boot so existing config files that still
     * carry 0 are upgraded; the user can still raise it in the settings.
     *
     * "Shader Mode = Async Recompiler" skips the GPU shader-interpreter precompile
     * ("Precompiling interpreter variants") that otherwise runs on every first boot
     * with a cold shader cache and can take minutes on a mobile driver. Android
     * ports effectively ship this behaviour: their GLES path auto-downgrades the
     * mode when bindless textures are unsupported, and the precompile never runs
     * there. The cost is losing the interpreter safety net for shaders a driver
     * cannot compile natively -- rare games may show artifacts or a black screen
     * where they would otherwise fall back. Per-title, users can flip
     * "Video@@Shader Mode" back in the settings screen.
     */
    val RECOMMENDED_DEFAULTS = mapOf(
        "Core@@Max LLVM Compile Threads" to "2",
        "Video@@Shader Mode" to "\"Async Recompiler (multi-threaded)\"",
        // Colour buffer readback/writeback avoid black screens on mobile drivers.
        "Video@@Write Color Buffers" to "true",
        "Video@@Read Color Buffers" to "true",
        // Match the console cadence instead of running uncapped.
        "Video@@Frame limit" to "\"PS3 Native\"",
        // The GPU has no VRAM of its own on Android: upstream's default of 65536 MB means "no
        // limit", so the texture cache never evicts and grows until an allocation fails, which
        // is fatal. Cap it so the caches evict instead of killing the renderer.
        "Video@@VRAM allocation limit (MB)" to "2048",
        // EmuCoreC overlay look: mobile-friendly bold text on the right side.
        // The font is the standard PS3 bold system font from the installed
        // firmware (Rodin Bold); applied on every boot so config files saved
        // with the old defaults are upgraded. The user can still change both.
        "Video@@Performance Overlay@@Font size (px)" to "18",
        "Video@@Performance Overlay@@Position" to "\"Top Right\"",
        "Video@@Performance Overlay@@Font" to "\"SCE-PS3-RD-B-LATIN.TTF\"",
        // Near-opaque overlay text for readability.
        "Video@@Performance Overlay@@Opacity (%)" to "97",
    )

    fun recordGlobal(context: Context, path: String, encodedValue: String) {
        val prefs = prefs(context)
        val global = read(prefs.getString(KEY_GLOBAL, null)).toMutableMap()
        val baseline = read(prefs.getString(KEY_BASELINE, null)).toMutableMap()
        global[path] = encodedValue
        baseline[path] = encodedValue
        prefs.edit()
            .putString(KEY_GLOBAL, encode(global))
            .putString(KEY_BASELINE, encode(baseline))
            .apply()
    }

    fun recordGame(
        context: Context,
        titleId: String,
        path: String,
        encodedValue: String,
        previousEncodedValue: String,
    ) {
        val id = normalizedTitleId(titleId) ?: return
        val prefs = prefs(context)
        val baseline = read(prefs.getString(KEY_BASELINE, null)).toMutableMap()
        if (path !in baseline) baseline[path] = previousEncodedValue
        val key = GAME_PREFIX + id
        val game = read(prefs.getString(key, null)).toMutableMap()
        game[path] = encodedValue
        prefs.edit()
            .putString(KEY_BASELINE, encode(baseline))
            .putString(key, encode(game))
            .apply()
    }

    fun clearGame(context: Context, titleId: String) {
        val id = normalizedTitleId(titleId) ?: return
        prefs(context).edit().remove(GAME_PREFIX + id).apply()
    }

    fun gameOverrideCount(context: Context, titleId: String): Int {
        val id = normalizedTitleId(titleId) ?: return 0
        return read(prefs(context).getString(GAME_PREFIX + id, null)).size
    }

    fun gameOverrides(context: Context, titleId: String): Map<String, String> {
        val id = normalizedTitleId(titleId) ?: return emptyMap()
        return read(prefs(context).getString(GAME_PREFIX + id, null))
    }

    /** Global values used as the base layer when editing a per-title profile. */
    fun resolvedGlobalValues(context: Context): Map<String, String> {
        val prefs = prefs(context)
        return LinkedHashMap(RECOMMENDED_DEFAULTS).apply {
            putAll(read(prefs.getString(KEY_BASELINE, null)))
            putAll(read(prefs.getString(KEY_GLOBAL, null)))
        }
    }

    /** Serialize every native global/per-title override for settings backups. */
    fun exportJson(context: Context): JSONObject {
        val prefs = prefs(context)
        val games = JSONObject()
        prefs.all.forEach { (key, raw) ->
            if (key.startsWith(GAME_PREFIX) && raw is String) {
                games.put(key.removePrefix(GAME_PREFIX), JSONObject(raw))
            }
        }
        return JSONObject()
            .put(KEY_GLOBAL, JSONObject(prefs.getString(KEY_GLOBAL, null) ?: "{}"))
            .put(KEY_BASELINE, JSONObject(prefs.getString(KEY_BASELINE, null) ?: "{}"))
            .put("games", games)
    }

    /** Restore native overrides from a trusted app backup and apply the global tier. */
    fun restoreJson(context: Context, root: JSONObject) {
        val global = readObject(root.optJSONObject(KEY_GLOBAL))
        val baseline = readObject(root.optJSONObject(KEY_BASELINE))
        val editor = prefs(context).edit().clear()
            .putString(KEY_GLOBAL, encode(global))
            .putString(KEY_BASELINE, encode(baseline))
        root.optJSONObject("games")?.let { games ->
            games.keys().forEach { rawTitleId ->
                val titleId = normalizedTitleId(rawTitleId) ?: return@forEach
                val values = readObject(games.optJSONObject(rawTitleId))
                if (values.isNotEmpty()) editor.putString(GAME_PREFIX + titleId, encode(values))
            }
        }
        editor.apply()
        if (Ps3Runtime.ensureInitialized(context)) applyForGame(context, null)
    }

    /** Remove one per-title override and immediately restore its global value. */
    fun clearGameSetting(
        context: Context,
        titleId: String,
        path: String,
        coreDefaultEncodedValue: String,
    ): Boolean {
        val id = normalizedTitleId(titleId) ?: return false
        val prefs = prefs(context)
        val key = GAME_PREFIX + id
        val game = read(prefs.getString(key, null)).toMutableMap()
        game.remove(path)

        val editor = prefs.edit()
        if (game.isEmpty()) editor.remove(key) else editor.putString(key, encode(game))
        editor.apply()

        val baseline = read(prefs.getString(KEY_BASELINE, null))
        val global = read(prefs.getString(KEY_GLOBAL, null))
        val restored = global[path] ?: baseline[path] ?: coreDefaultEncodedValue
        val applied = runCatching { RPCSX.instance.settingsSet(path, restored) }.getOrDefault(false)
        if (applied) CoreSettingsTreeCache.invalidateTree()
        return applied
    }

    /** Restore every live RPCS3 setting to the default reported by this core build. */
    fun resetAllToCoreDefaults(context: Context): Boolean {
        if (!Ps3Runtime.ensureInitialized(context)) return false
        val tree = runCatching { JSONObject(RPCSX.instance.settingsGet("")) }.getOrNull()
            ?: return false
        val defaults = linkedMapOf<String, String>()
        collectDefaults(tree, "", defaults)
        defaults.putAll(RECOMMENDED_DEFAULTS)
        if (defaults.isEmpty()) return false

        var accepted = true
        defaults.forEach { (path, value) ->
            if (!runCatching { RPCSX.instance.settingsSet(path, value) }.getOrDefault(false)) {
                accepted = false
                Log.w(TAG, "RPCS3 rejected default for $path")
            }
        }
        // Invalidate even on partial failure: some values may have changed.
        CoreSettingsTreeCache.invalidateTree()
        prefs(context).edit().clear().apply()
        return accepted
    }

    /** Apply the global baseline and then the selected title's values before boot. */
    fun applyForGame(context: Context, titleId: String?) {
        if (!RPCSX.initialized) return
        val prefs = prefs(context)
        val baseline = read(prefs.getString(KEY_BASELINE, null))
        val global = read(prefs.getString(KEY_GLOBAL, null))
        val game = normalizedTitleId(titleId)
            ?.let { read(prefs.getString(GAME_PREFIX + it, null)) }
            .orEmpty()
        val resolvedBase = LinkedHashMap(RECOMMENDED_DEFAULTS).apply {
            putAll(baseline)
            putAll(global)
        }
        if (resolvedBase.isEmpty() && game.isEmpty()) return

        resolvedBase.forEach { (path, value) -> set(path, value, "global") }
        game.forEach { (path, value) -> set(path, value, "game:${titleId.orEmpty()}") }
    }

    private fun set(path: String, value: String, tier: String) {
        val applied = runCatching { RPCSX.instance.settingsSet(path, value) }.getOrDefault(false)
        if (applied) CoreSettingsTreeCache.invalidateTree()
        if (!applied) Log.w(TAG, "RPCS3 rejected [$tier] $path")
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun normalizedTitleId(value: String?): String? = value
        ?.trim()
        ?.uppercase()
        ?.takeIf { it.matches(Regex("[A-Z0-9_.-]{3,64}")) }

    private fun read(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            buildMap { json.keys().forEach { key -> put(key, json.getString(key)) } }
        }.getOrDefault(emptyMap())
    }

    private fun encode(values: Map<String, String>): String = JSONObject().apply {
        values.forEach { (key, value) -> put(key, value) }
    }.toString()

    private fun readObject(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        return buildMap {
            json.keys().forEach { path ->
                val value = json.optString(path, "")
                if (path.isNotBlank() && value.isNotBlank() && runCatching {
                        JSONObject("{\"value\":$value}")
                    }.isSuccess
                ) {
                    put(path, value)
                }
            }
        }
    }

    private fun collectDefaults(
        node: JSONObject,
        prefix: String,
        out: MutableMap<String, String>,
    ) {
        node.keys().forEach { key ->
            val child = node.optJSONObject(key) ?: return@forEach
            val path = if (prefix.isEmpty()) key else "$prefix@@$key"
            val type = child.optString("type")
            if (type.isEmpty()) {
                collectDefaults(child, path, out)
            } else {
                out[path] = when (type) {
                    "bool" -> child.optBoolean("default").toString()
                    "int", "uint", "float" -> child.optString("default")
                    else -> JSONObject.quote(child.optString("default"))
                }
            }
        }
    }
}
