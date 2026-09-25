package com.sbro.emucorec.core

import android.content.Context
import android.net.Uri
import com.sbro.emucorec.data.AppFont
import com.sbro.emucorec.data.AppPreferences
import com.sbro.emucorec.data.CustomizationPreferences
import org.json.JSONObject
import java.io.File

class SettingsBackupRepository(
    private val context: Context,
    private val preferences: AppPreferences,
    private val coreConfigRepository: Ps3CoreConfigRepository,
    private val customizationPreferences: CustomizationPreferences,
) {
    fun exportJson(): JSONObject {
        val current = customizationPreferences.current
        return JSONObject()
            .put("format", BACKUP_FORMAT_VERSION)
            .put(
                "app",
                JSONObject()
                    .putNullable("packagesFolderUri", preferences.packagesFolderUri)
                    .putNullable("ps3StorageRootPath", preferences.ps3StorageRootPath)
                    .put("onboardingCompleted", preferences.onboardingCompleted)
                    .put("themeMode", preferences.themeMode.name)
                    .put("appLanguage", preferences.appLanguage.name)
                    .putNullable("skippedUpdateTag", preferences.skippedUpdateTag),
            )
            .put("androidControls", coreConfigRepository.ensureDefaultsPersisted().toJsonObject())
            .put("nativeCore", Ps3CoreSettingOverrides.exportJson(context))
            .put(
                "customization",
                JSONObject()
                    .put("coverSizePercent", current.coverSizePercent)
                    .put("appFont", current.appFont.takeUnless { it == AppFont.CUSTOM }?.name ?: AppFont.SYSTEM.name)
                    .put("textSizePercent", current.textSizePercent)
                    .put("touchControlVisualStyle", current.touchControlVisualStyle.name)
                    .put("touchControlPressEffect", current.touchControlPressEffect.name)
                    .put("gameMenuLayoutStyle", current.gameMenuLayoutStyle.name)
                    .put("drawerVisualStyle", current.drawerVisualStyle.name)
                    .putNullable("customFontPath", current.customFontPath)
                    .putNullable("backgroundPath", current.backgroundPath)
                    .putNullable("backgroundMimeType", current.backgroundMimeType),
            )
    }

    fun exportTo(uri: Uri) {
        val root = exportJson()
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(root.toString(2).toByteArray(Charsets.UTF_8))
        } ?: error("Could not open backup destination")
    }

    /**
     * Applies a portable settings payload without touching device-local paths (storage root and
     * packages folder stay on the device that performs the restore). Device storage locations are
     * only applied by [restoreFrom] for user-created local files.
     */
    fun restoreJson(
        root: JSONObject,
        applyApp: Boolean = true,
        applyCore: Boolean = true,
        applyCustomization: Boolean = true,
    ): Ps3CoreConfig {
        if (applyApp) root.optJSONObject("app")?.let { app ->
            preferences.themeMode = app.optEnum("themeMode", preferences.themeMode)
            preferences.appLanguage = app.optEnum("appLanguage", preferences.appLanguage)
            preferences.applyAppLanguage()
        }

        if (applyCustomization) root.optJSONObject("customization")?.let { customization ->
            customizationPreferences.setCoverSizePercent(
                customization.optInt("coverSizePercent", customizationPreferences.current.coverSizePercent),
            )
            if (customization.has("customFontPath")) {
                val customFontPath = customization.optNullableString("customFontPath")
                val requestedFont = customization.optEnum("appFont", AppFont.SYSTEM)
                if (requestedFont == AppFont.CUSTOM && customFontPath != null && File(customFontPath).isFile) {
                    customizationPreferences.setAppFont(AppFont.CUSTOM, customFontPath)
                } else {
                    customizationPreferences.setAppFont(AppFont.SYSTEM, null)
                }
            } else {
                customizationPreferences.setAppFont(customization.optEnum("appFont", AppFont.SYSTEM))
            }
            if (customization.has("backgroundPath")) {
                val backgroundPath = customization.optNullableString("backgroundPath")
                val backgroundMimeType = customization.optNullableString("backgroundMimeType")
                if (backgroundPath != null && backgroundMimeType != null && File(backgroundPath).isFile) {
                    customizationPreferences.setBackground(backgroundPath, backgroundMimeType)
                } else {
                    customizationPreferences.clearBackground()
                }
            }
            customizationPreferences.setTextSizePercent(
                customization.optInt("textSizePercent", customizationPreferences.current.textSizePercent),
            )
            customizationPreferences.setTouchControlVisualStyle(
                customization.optEnum(
                    "touchControlVisualStyle",
                    customizationPreferences.current.touchControlVisualStyle,
                ),
            )
            customizationPreferences.setTouchControlPressEffect(
                customization.optEnum(
                    "touchControlPressEffect",
                    customizationPreferences.current.touchControlPressEffect,
                ),
            )
            customizationPreferences.setGameMenuLayoutStyle(
                customization.optEnum("gameMenuLayoutStyle", customizationPreferences.current.gameMenuLayoutStyle),
            )
            customizationPreferences.setDrawerVisualStyle(
                customization.optEnum("drawerVisualStyle", customizationPreferences.current.drawerVisualStyle),
            )
        }

        if (applyCore) {
            val defaults = coreConfigRepository.ensureDefaultsPersisted()
            val restored = root.optJSONObject("androidControls")?.toPs3CoreConfig(defaults) ?: defaults
            coreConfigRepository.save(restored)
            root.optJSONObject("nativeCore")?.let { Ps3CoreSettingOverrides.restoreJson(context, it) }
        }
        return coreConfigRepository.ensureDefaultsPersisted()
    }

    fun restoreFrom(uri: Uri): Ps3CoreConfig {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: error("Could not open backup file")
        val root = JSONObject(text)
        require(root.optInt("format", -1) in SUPPORTED_BACKUP_FORMATS) {
            "Unsupported settings backup format"
        }

        // A user-selected local backup is device migration, so storage locations travel with it.
        root.optJSONObject("app")?.let { app ->
            preferences.packagesFolderUri = app.optNullableString("packagesFolderUri")
            preferences.ps3StorageRootPath = app.optNullableString("ps3StorageRootPath")
            preferences.onboardingCompleted = app.optBoolean("onboardingCompleted", preferences.onboardingCompleted)
            preferences.skippedUpdateTag = app.optNullableString("skippedUpdateTag")
        }
        return restoreJson(root, applyApp = true, applyCore = true, applyCustomization = true)
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)

    private inline fun <reified T : Enum<T>> JSONObject.optEnum(name: String, fallback: T): T {
        val value = optString(name, fallback.name)
        return enumValues<T>().firstOrNull { it.name == value } ?: fallback
    }

    private fun JSONObject.putNullable(name: String, value: String?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private companion object {
        const val BACKUP_FORMAT_VERSION = 3
        val SUPPORTED_BACKUP_FORMATS = 2..BACKUP_FORMAT_VERSION
    }
}
