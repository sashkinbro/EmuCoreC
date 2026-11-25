package com.sbro.emucorec.data.store

import android.content.Context
import android.graphics.Color
import android.net.Uri
import com.sbro.emucorec.data.AppFont
import com.sbro.emucorec.data.AppPreferences
import com.sbro.emucorec.data.CustomizationFileStore
import com.sbro.emucorec.data.CustomizationPreferences
import com.sbro.emucorec.data.TouchControlPressEffect
import com.sbro.emucorec.data.theme.ThemePackageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class StoreApplyResult { APPLIED, NEEDS_PRO, NOT_INSTALLED, UNSUPPORTED, FAILED }

/** An installed Store controls skin, resolved to files. Rendering is up to the touch-overlay UI. */
data class ControlSkin(
    val dir: File,
    /** control id -> image. */
    val images: Map<String, File>,
    /** control id -> image shown while pressed (from the "<id>_pressed" entries). */
    val pressed: Map<String, File>,
    /** ARGB tint, or null for none. */
    val tint: Int?,
    /** 0..1. */
    val opacity: Float,
    /** Null = keep the user's press-effect preference. */
    val pressEffect: TouchControlPressEffect?
)

object StoreControlSkins {
    /** Loads the skin of installed controls package [packId]; null if missing, layout-only or unreadable. */
    fun load(context: Context, packId: String?): ControlSkin? {
        if (packId.isNullOrBlank() || !StoreInstalls.ID_PATTERN.matches(packId)) return null
        val dir = File(StoreInstalls.kindDirectory(context, StoreKind.CONTROLS), packId)
        return runCatching {
            val manifest = StoreInstalls.JSON.decodeFromString<ControlsManifest>(
                File(dir, StoreInstalls.CONTROLS_JSON).readText()
            )
            val skin = manifest.skin?.takeIf { it.images.isNotEmpty() } ?: return null
            val images = LinkedHashMap<String, File>()
            val pressed = LinkedHashMap<String, File>()
            skin.images.forEach { (controlId, name) ->
                val file = File(dir, name).takeIf { it.isFile && it.parentFile == dir } ?: return@forEach
                if (controlId.endsWith(PRESSED_SUFFIX)) {
                    pressed[controlId.removeSuffix(PRESSED_SUFFIX)] = file
                } else {
                    images[controlId] = file
                }
            }
            if (images.isEmpty()) return null
            ControlSkin(
                dir = dir,
                images = images,
                pressed = pressed,
                tint = skin.tint?.let { runCatching { Color.parseColor(it) }.getOrNull() },
                opacity = skin.opacity.coerceIn(0f, 1f),
                pressEffect = skin.pressEffect?.let { name ->
                    TouchControlPressEffect.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                }
            )
        }.getOrNull()
    }

    private const val PRESSED_SUFFIX = "_pressed"
}

/**
 * Applies installed Store items through the existing preference/repository paths, and removes them.
 * No UI. Sound packs are not offered by this app (see StorePlatform.SUPPORTED_KINDS).
 */
class StoreApplier(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = AppPreferences(appContext)
    private val installs = StoreInstalls.getInstance(appContext)

    suspend fun apply(item: StoreItem): StoreApplyResult = withContext(Dispatchers.IO) {
        try {
            val installed = installs.find(item.id)?.takeIf { it.kind == item.kind }
                ?: return@withContext StoreApplyResult.NOT_INSTALLED
            if (item.minAppVersionCode > StoreClient.ENGINE_VERSION_CODE) return@withContext StoreApplyResult.UNSUPPORTED
            when (item.kind) {
                StoreKind.THEME -> applyTheme(installed)
                StoreKind.WALLPAPER -> applyWallpaper(installed)
                StoreKind.CONTROLS -> applyControls(installed)
                StoreKind.FONT -> applyFont(installed)
                StoreKind.SOUNDS -> StoreApplyResult.UNSUPPORTED
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            StoreApplyResult.FAILED
        }
    }

    /** Deletes the installed files and resets anything that pointed at them (applied theme, controls skin). */
    suspend fun remove(id: String): Boolean {
        val installed = installs.find(id) ?: return false
        when (installed.kind) {
            StoreKind.THEME -> preferences.clearThemePackage(id)
            StoreKind.CONTROLS -> if (preferences.touchControlSkinPack == id) preferences.touchControlSkinPack = null
            // The wallpaper and an applied font are independent copies.
            StoreKind.SOUNDS, StoreKind.WALLPAPER, StoreKind.FONT -> Unit
        }
        return installs.remove(id)
    }

    private fun applyTheme(installed: InstalledStoreItem): StoreApplyResult {
        val pack = ThemePackageRepository.getInstance(appContext).find(installed.id)
            ?: return StoreApplyResult.NOT_INSTALLED
        if (!pack.isSupported) return StoreApplyResult.UNSUPPORTED
        preferences.applyThemePackage(pack.id)
        return StoreApplyResult.APPLIED
    }

    private fun applyWallpaper(installed: InstalledStoreItem): StoreApplyResult {
        val image = StorePackageInstaller.WALLPAPER_IMAGES.map { File(installed.directory, it) }.firstOrNull(File::isFile)
            ?: return StoreApplyResult.NOT_INSTALLED
        val imported = runCatching { CustomizationFileStore(appContext).importBackground(Uri.fromFile(image)) }
            .getOrElse { return StoreApplyResult.FAILED }
        val customization = CustomizationPreferences(appContext)
        try {
            customization.setBackground(imported.path, imported.mimeType)
        } finally {
            customization.close()
        }
        return StoreApplyResult.APPLIED
    }

    private fun applyFont(installed: InstalledStoreItem): StoreApplyResult {
        val font = StoreFontPacks.file(appContext, installed.id) ?: return StoreApplyResult.NOT_INSTALLED
        val path = runCatching { CustomizationFileStore(appContext).importFont(Uri.fromFile(font)) }
            .getOrElse { return StoreApplyResult.FAILED }
        val customization = CustomizationPreferences(appContext)
        try {
            customization.setAppFont(AppFont.CUSTOM, path, storeFontId = installed.id)
        } finally {
            customization.close()
        }
        return StoreApplyResult.APPLIED
    }

    private fun applyControls(installed: InstalledStoreItem): StoreApplyResult {
        val manifest = StoreInstalls.JSON.decodeFromString<ControlsManifest>(
            File(installed.directory, StoreInstalls.CONTROLS_JSON).readText()
        )
        // Only the skin is applied: this app's touch layouts are not part of the Store format.
        if (manifest.skin?.images?.isNotEmpty() != true) return StoreApplyResult.UNSUPPORTED
        preferences.touchControlSkinPack = installed.id
        return StoreApplyResult.APPLIED
    }
}
