package com.sbro.emucorec.core

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

data class Ps3GameSettingsProfile(
    val config: Ps3CoreConfig,
    val customDriverOverride: String?,
)

/** Per-title overrides for EmuCoreC-owned Android controls and custom GPU drivers. */
class Ps3GameSettingsRepository(context: Context) {
    private val globalRepository = Ps3CoreConfigRepository(context)
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasCustomConfig(titleId: String): Boolean = preferences.contains(key(titleId))

    fun loadEffective(titleId: String): Ps3CoreConfig = loadProfile(titleId).config

    fun loadProfile(titleId: String): Ps3GameSettingsProfile {
        val base = globalRepository.ensureDefaultsPersisted()
        val raw = preferences.getString(key(titleId), null)
            ?: return Ps3GameSettingsProfile(base, null)
        return runCatching {
            val root = JSONObject(raw)
            val hasDriverOverride = root.optBoolean("hasDriverOverride", false)
            val driverOverride = if (hasDriverOverride) root.optString("driverOverride", "") else null
            val stored = root.optJSONObject("config")?.toPs3CoreConfig(base) ?: base
            Ps3GameSettingsProfile(
                config = stored.copy(customDriverName = driverOverride ?: base.customDriverName).normalized(),
                customDriverOverride = driverOverride,
            )
        }.getOrDefault(Ps3GameSettingsProfile(base, null))
    }

    fun save(titleId: String, config: Ps3CoreConfig) = savePreservingDriverOverride(titleId, config)

    fun savePreservingDriverOverride(titleId: String, config: Ps3CoreConfig) {
        val override = loadProfile(titleId).customDriverOverride
        saveProfile(titleId, config, override)
    }

    fun saveProfile(titleId: String, config: Ps3CoreConfig, customDriverOverride: String?) {
        if (titleId.isBlank()) return
        val base = globalRepository.ensureDefaultsPersisted()
        val effective = config.copy(customDriverName = customDriverOverride ?: base.customDriverName).normalized()
        val root = JSONObject()
            .put("config", effective.toJsonObject())
            .put("hasDriverOverride", customDriverOverride != null)
            .put("driverOverride", customDriverOverride.orEmpty())
        preferences.edit(commit = true) { putString(key(titleId), root.toString()) }
    }

    fun syncEffectiveDriverForLaunch(titleId: String) {
        if (hasCustomConfig(titleId)) loadProfile(titleId)
    }

    fun update(titleId: String, transform: (Ps3CoreConfig) -> Ps3CoreConfig): Ps3CoreConfig {
        val profile = loadProfile(titleId)
        val updated = transform(profile.config).normalized()
        saveProfile(titleId, updated, profile.customDriverOverride)
        return updated
    }

    fun reset(titleId: String) {
        if (titleId.isNotBlank()) preferences.edit(commit = true) { remove(key(titleId)) }
    }

    /** Portable snapshot of every per-title override, keyed by their storage key. */
    fun exportAll(): Map<String, String> = preferences.all.entries
        .filter { (name, value) -> name.startsWith(GAME_KEY_PREFIX) && value is String }
        .associate { (name, value) -> name to (value as String) }

    fun importAll(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        preferences.edit(commit = true) {
            entries.forEach { (name, value) ->
                if (name.matches(GAME_KEY_PATTERN)) putString(name, value)
            }
        }
    }

    private fun key(titleId: String): String = "game_${titleId.trim().uppercase()}"

    private companion object {
        const val PREFS_NAME = "emucorec_game_ui_config"
        const val GAME_KEY_PREFIX = "game_"
        val GAME_KEY_PATTERN = Regex("game_[A-Za-z0-9_\\-]{1,40}")
    }
}
