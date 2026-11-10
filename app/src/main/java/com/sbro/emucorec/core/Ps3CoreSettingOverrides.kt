package com.sbro.emucorec.core

import android.content.Context
import android.util.Log
import org.json.JSONObject

internal val PRESENTATION_DEFAULTS = linkedMapOf(
    "Video@@Cardboard VR" to "false",
    "Video@@VR Picture Size" to "80",
    "Video@@VR Eye Spacing" to "0",
    "Video@@VR Height" to "0",
)

internal data class CardboardPresentation(val enabled: Boolean, val size: Int, val spacing: Int, val vertical: Int)

internal fun resolveCardboardPresentation(values: Map<String, String>): CardboardPresentation = CardboardPresentation(
    enabled = values["Video@@Cardboard VR"]?.toBooleanStrictOrNull() ?: false,
    size = (values["Video@@VR Picture Size"]?.toIntOrNull() ?: 80).coerceIn(20, 100),
    spacing = (values["Video@@VR Eye Spacing"]?.toIntOrNull() ?: 0).coerceIn(-100, 100),
    vertical = (values["Video@@VR Height"]?.toIntOrNull() ?: 0).coerceIn(-100, 100),
)

internal fun withTitleHostnameRedirects(userList: String, titleId: String?): String {
    // ARMSX3 47b4510a8a: Sony's retired Demon's Souls hosts otherwise resolve to parked
    // domains. Defaults are per title and every explicit user host keeps its address.
    if (titleId?.trim()?.uppercase() !in setOf(
            "BLUS30443", "BLES00932", "BCJS30022", "BCAS20071", "NPUB30910", "NPEB01202", "NPJA00102",
        )) return userList
    val entries = userList.split("&&").map(String::trim).filter(String::isNotEmpty)
    val userHosts = entries.map { it.substringBefore('=').trim().lowercase() }.toSet()
    val hosts = listOf(
        "ds-eu-c.scej-online.jp", "ds-eu-g.scej-online.jp", "c.demons-souls.com",
        "g.demons-souls.com", "cmnap.scej-online.jp", "demons-souls.scej-online.jp",
    )
    return (entries + hosts.filterNot(userHosts::contains).map { "$it=206.189.232.242" }).joinToString("&&")
}

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
     * Automatic LLVM parallelism follows the work and available memory in the
     * core. Explicit user limits are still replayed after these defaults.
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
        "Core@@Max LLVM Compile Threads" to "0",
        "Video@@Shader Mode" to "\"Async Recompiler (multi-threaded)\"",
        // Colour buffer readback/writeback avoid black screens on mobile drivers.
        "Video@@Write Color Buffers" to "true",
        "Video@@Read Color Buffers" to "true",
        // Match the console cadence instead of running uncapped.
        "Video@@Frame limit" to "\"PS3 Native\"",
        // The GPU has no VRAM of its own on Android: upstream's default of 65536 MB means "no
        // limit", so the texture cache never evicts and grows until an allocation fails, which
        // is fatal. Cap it so the caches evict instead of killing the renderer.
        "Video@@Vulkan@@VRAM allocation limit (MB)" to "2048",
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

    /** Desired values explicitly remembered by the app, excluding recommended defaults. */
    fun savedGlobalValues(context: Context): Map<String, String> {
        val prefs = prefs(context)
        return LinkedHashMap(read(prefs.getString(KEY_BASELINE, null))).apply {
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

        if (path in PRESENTATION_DEFAULTS) {
            applyPresentationSettings(context, titleId)
            return true
        }

        val baseline = read(prefs.getString(KEY_BASELINE, null))
        val global = read(prefs.getString(KEY_GLOBAL, null))
        val restored = global[path] ?: baseline[path] ?: coreDefaultEncodedValue
        val applied = runCatching { Ps3Core.instance.settingsSetTransient(path, restored) }.getOrDefault(false)
        if (applied) CoreSettingsTreeCache.invalidateTree()
        return applied
    }

    /** Restore every live RPCS3 setting to the default reported by this core build. */
    fun resetAllToCoreDefaults(context: Context): Boolean {
        if (!Ps3Runtime.ensureInitialized(context)) return false
        val tree = runCatching { JSONObject(Ps3Core.instance.settingsGet("")) }.getOrNull()
            ?: return false
        val defaults = linkedMapOf<String, String>()
        collectDefaults(tree, "", defaults)
        defaults.putAll(RECOMMENDED_DEFAULTS)
        if (defaults.isEmpty()) return false

        var accepted = true
        defaults.forEach { (path, value) ->
            if (!runCatching { Ps3Core.instance.settingsSet(path, value) }.getOrDefault(false)) {
                accepted = false
                Log.w(TAG, "RPCS3 rejected default for $path")
            }
        }
        // Invalidate even on partial failure: some values may have changed.
        CoreSettingsTreeCache.invalidateTree()
        prefs(context).edit().clear().apply()
        applyPresentationSettings(context, null)
        return accepted
    }

    /** Apply the global baseline and then the selected title's values before boot. */
    fun applyForGame(context: Context, titleId: String?) {
        if (!Ps3Core.initialized) return
        Ps3Core.instance.clearPendingGameSettings()
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
        game.forEach { (path, value) -> set(path, value, "game:${titleId.orEmpty()}", transient = true) }
        applyPresentationSettings(context, titleId)
        val redirectPath = "Net@@IP swap list"
        val encodedRedirects = game[redirectPath] ?: resolvedBase[redirectPath]
        val redirects = if (encodedRedirects != null) {
            runCatching { JSONObject("{\"value\":$encodedRedirects}").getString("value") }.getOrDefault("")
        } else {
            runCatching { JSONObject(Ps3Core.instance.settingsGet(redirectPath)).optString("value") }.getOrDefault("")
        }
        val merged = withTitleHostnameRedirects(redirects, titleId)
        if (merged != redirects) {
            // Queue after all user layers, but never persist a community-server default globally.
            set(redirectPath, JSONObject.quote(merged), "title redirects", transient = true)
        }
    }

    private fun set(path: String, value: String, tier: String, transient: Boolean = false) {
        // These controls are owned by the Android presenter rather than cfg_root.
        if (path in PRESENTATION_DEFAULTS) return
        val applied = runCatching {
            if (transient) Ps3Core.instance.queueGameSetting(path, value)
            else Ps3Core.instance.settingsSet(path, value)
        }.getOrDefault(false)
        if (applied) CoreSettingsTreeCache.invalidateTree()
        if (!applied) Log.w(TAG, "RPCS3 rejected [$tier] $path")
    }

    internal fun validPresentationEdit(path: String, value: String): Boolean = when (path) {
        "Video@@Cardboard VR" -> value.toBooleanStrictOrNull() != null
        "Video@@VR Picture Size" -> value.toIntOrNull()?.let { it in 20..100 } == true
        "Video@@VR Eye Spacing", "Video@@VR Height" -> value.toIntOrNull()?.let { it in -100..100 } == true
        else -> false
    }

    internal fun applyPresentationSettings(context: Context, titleId: String?) {
        if (!Ps3Core.initialized) return
        val values = resolvedGlobalValues(context) + titleId?.let { gameOverrides(context, it) }.orEmpty()
        val state = resolveCardboardPresentation(values)
        Ps3Core.instance.setCardboardVr(state.enabled, state.size, state.spacing, state.vertical)
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
