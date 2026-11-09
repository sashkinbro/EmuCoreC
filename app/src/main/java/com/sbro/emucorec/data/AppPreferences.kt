package com.sbro.emucorec.data

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import androidx.documentfile.provider.DocumentFile
import com.sbro.emucorec.core.input.KeyboardMouseBindings
import com.sbro.emucorec.ui.theme.ThemeMode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class AppLanguage(val storageValue: Int, val languageTag: String) {
    SYSTEM(0, ""),
    ENGLISH(1, "en"),
    RUSSIAN(2, "ru"),
    UKRAINIAN(3, "uk"),
    SPANISH(4, "es"),
    FRENCH(5, "fr"),
    GERMAN(6, "de"),
    PORTUGUESE(7, "pt"),
    CHINESE(8, "zh"),
    HINDI(9, "hi"),
    ITALIAN(10, "it"),
    TURKISH(11, "tr"),
    ARABIC(12, "ar"),
    CZECH(13, "cs"),
    POLISH(14, "pl"),
    JAPANESE(15, "ja"),
    KOREAN(16, "ko"),
    INDONESIAN(17, "id"),
    PERSIAN(18, "fa");

    companion object {
        fun fromStorageValue(value: Int): AppLanguage = entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

class AppPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("emucorec_prefs", Context.MODE_PRIVATE)

    var packagesFolderUri: String?
        get() = prefs.getString(KEY_PACKAGES_FOLDER_URI, null)
        set(value) = prefs.edit { putString(KEY_PACKAGES_FOLDER_URI, value) }

    var ps3StorageRootPath: String?
        get() = prefs.getString(KEY_PS3_STORAGE_ROOT_PATH, null)
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_PS3_STORAGE_ROOT_PATH)
            } else {
                putString(KEY_PS3_STORAGE_ROOT_PATH, value)
            }
        }

    var onboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit { putBoolean(KEY_ONBOARDING_COMPLETED, value) }

    var lastCoreBinaryFingerprint: String?
        get() = prefs.getString(KEY_LAST_CORE_BINARY_FINGERPRINT, null)
        set(value) = prefs.edit { putString(KEY_LAST_CORE_BINARY_FINGERPRINT, value) }

    var themeMode: ThemeMode
        get() = when (prefs.getInt(KEY_THEME_MODE, 0)) {
            1 -> ThemeMode.LIGHT
            2 -> ThemeMode.DARK
            3 -> ThemeMode.NEON
            else -> ThemeMode.SYSTEM
        }
        set(value) {
            prefs.edit {
                putInt(
                    KEY_THEME_MODE,
                    when (value) {
                        ThemeMode.SYSTEM -> 0
                        ThemeMode.LIGHT -> 1
                        ThemeMode.DARK -> 2
                        ThemeMode.NEON -> 3
                    }
                )
            }
        }

    val themeModeFlow: Flow<ThemeMode>
        get() = callbackFlow {
            trySend(themeMode)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_THEME_MODE) {
                    trySend(themeMode)
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }.distinctUntilChanged()

    var appLanguage: AppLanguage
        get() = AppLanguage.fromStorageValue(prefs.getInt(KEY_APP_LANGUAGE, AppLanguage.SYSTEM.storageValue))
        set(value) = prefs.edit { putInt(KEY_APP_LANGUAGE, value.storageValue) }

    var skippedUpdateTag: String?
        get() = prefs.getString(KEY_SKIPPED_UPDATE_TAG, null)
        set(value) = prefs.edit { putString(KEY_SKIPPED_UPDATE_TAG, value) }

    fun packagesFolderUriAsUri(): Uri? = packagesFolderUri?.let(Uri::parse)

    fun applyAppLanguage() {
        val tag = if (appLanguage == AppLanguage.SYSTEM) "" else appLanguage.languageTag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = appContext.getSystemService(LocaleManager::class.java)
            manager?.applicationLocales = if (tag.isEmpty()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(tag)
            }
        } else {
            val locales = if (tag.isEmpty()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    fun setPackagesFolder(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        val previous = packagesFolderUriAsUri()
        if (previous != null && previous != uri) {
            releasePersistedPermission(resolver, previous)
        }
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        packagesFolderUri = uri.toString()
    }

    fun clearPackagesFolder(context: Context) {
        packagesFolderUriAsUri()?.let { releasePersistedPermission(context.contentResolver, it) }
        packagesFolderUri = null
    }

    fun packagesFolderDisplayName(context: Context): String? {
        val uri = packagesFolderUriAsUri() ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
            ?: uri.lastPathSegment?.substringAfterLast(':')
            ?: uri.toString()
    }

    private fun releasePersistedPermission(
        resolver: android.content.ContentResolver,
        uri: Uri
    ) {
        runCatching {
            resolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }

    var showEmulationQuickBar: Boolean
        get() = prefs.getBoolean(KEY_SHOW_EMULATION_QUICK_BAR, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_EMULATION_QUICK_BAR, value) }

    val showEmulationQuickBarFlow: Flow<Boolean>
        get() = callbackFlow {
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_SHOW_EMULATION_QUICK_BAR) trySend(showEmulationQuickBar)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            trySend(showEmulationQuickBar)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }.distinctUntilChanged()

    val keepScreenOnFlow: Flow<Boolean>
        get() = callbackFlow {
            trySend(keepScreenOn)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_KEEP_SCREEN_ON) {
                    trySend(keepScreenOn)
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }.distinctUntilChanged()

    var gameDirectories: Set<String>
        get() = prefs.getStringSet(KEY_GAME_DIRECTORIES, emptySet()) ?: emptySet()
        set(value) = prefs.edit { putStringSet(KEY_GAME_DIRECTORIES, value) }

    fun addGameDirectory(pathOrUri: String) {
        val current = gameDirectories.toMutableSet()
        current.add(pathOrUri)
        gameDirectories = current
    }

    fun removeGameDirectory(pathOrUri: String) {
        val current = gameDirectories.toMutableSet()
        current.remove(pathOrUri)
        gameDirectories = current
    }

    val keyboardMouseSettings: KeyboardMouseSettings
        get() = KeyboardMouseSettings(
            keyboardEnabled = prefs.getBoolean(KEY_KEYBOARD_ENABLED, true),
            mouseEnabled = prefs.getBoolean(KEY_MOUSE_ENABLED, true),
            keyboardTargetPad = prefs.getInt(KEY_KEYBOARD_TARGET_PAD, 0).coerceIn(0, 1),
            mouseTargetPad = prefs.getInt(KEY_MOUSE_TARGET_PAD, 0).coerceIn(0, 1),
            keyboardBindingsByPad = decodeBindings(prefs.getString(KEY_KEYBOARD_BINDINGS, null)),
            mouseBindingsByPad = decodeBindings(prefs.getString(KEY_MOUSE_BINDINGS, null)),
            mouseAimMode = prefs.getInt(KEY_MOUSE_AIM_MODE, MOUSE_AIM_OFF)
                .coerceIn(MOUSE_AIM_OFF, MOUSE_AIM_LEFT_STICK),
            mouseSensitivity = prefs.getInt(KEY_MOUSE_SENSITIVITY, DEFAULT_MOUSE_SENSITIVITY)
                .coerceIn(MOUSE_SENSITIVITY_MIN, MOUSE_SENSITIVITY_MAX),
            mouseVerticalSensitivity = prefs.getInt(
                KEY_MOUSE_VERTICAL_SENSITIVITY,
                DEFAULT_MOUSE_VERTICAL_SENSITIVITY
            ).coerceIn(MOUSE_VERTICAL_SENSITIVITY_MIN, MOUSE_VERTICAL_SENSITIVITY_MAX),
            mouseSmoothing = prefs.getInt(KEY_MOUSE_SMOOTHING, DEFAULT_MOUSE_SMOOTHING)
                .coerceIn(MOUSE_SMOOTHING_MIN, MOUSE_SMOOTHING_MAX),
            mouseAimDeadzone = prefs.getInt(KEY_MOUSE_AIM_DEADZONE, DEFAULT_MOUSE_DEADZONE)
                .coerceIn(MOUSE_DEADZONE_MIN, MOUSE_DEADZONE_MAX),
            mouseInvertX = prefs.getBoolean(KEY_MOUSE_INVERT_X, false),
            mouseInvertY = prefs.getBoolean(KEY_MOUSE_INVERT_Y, false),
            mouseCaptureCursor = prefs.getBoolean(KEY_MOUSE_CAPTURE_CURSOR, true),
            mouseLeftHanded = prefs.getBoolean(KEY_MOUSE_LEFT_HANDED, false),
            mouseWheelMode = prefs.getInt(KEY_MOUSE_WHEEL_MODE, MOUSE_WHEEL_OFF)
                .coerceIn(MOUSE_WHEEL_OFF, MOUSE_WHEEL_SHOULDERS),
            mouseWheelPulseMs = prefs.getInt(
                KEY_MOUSE_WHEEL_PULSE_MS,
                DEFAULT_MOUSE_WHEEL_PULSE_MS
            ).coerceIn(MOUSE_WHEEL_PULSE_MIN_MS, MOUSE_WHEEL_PULSE_MAX_MS)
        )

    val keyboardMouseSettingsFlow: Flow<KeyboardMouseSettings>
        get() = callbackFlow {
            trySend(keyboardMouseSettings)
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key != null && key in KEYBOARD_MOUSE_KEYS) {
                    trySend(keyboardMouseSettings)
                }
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }.distinctUntilChanged()

    fun setKeyboardMouseSettings(settings: KeyboardMouseSettings) {
        prefs.edit {
            putBoolean(KEY_KEYBOARD_ENABLED, settings.keyboardEnabled)
            putBoolean(KEY_MOUSE_ENABLED, settings.mouseEnabled)
            putInt(KEY_KEYBOARD_TARGET_PAD, settings.keyboardTargetPad.coerceIn(0, 1))
            putInt(KEY_MOUSE_TARGET_PAD, settings.mouseTargetPad.coerceIn(0, 1))
            putString(
                KEY_KEYBOARD_BINDINGS,
                encodeBindings(settings.keyboardBindingsByPad)
            )
            putString(KEY_MOUSE_BINDINGS, encodeBindings(settings.mouseBindingsByPad))
            putInt(
                KEY_MOUSE_AIM_MODE,
                settings.mouseAimMode.coerceIn(MOUSE_AIM_OFF, MOUSE_AIM_LEFT_STICK)
            )
            putInt(
                KEY_MOUSE_SENSITIVITY,
                settings.mouseSensitivity.coerceIn(MOUSE_SENSITIVITY_MIN, MOUSE_SENSITIVITY_MAX)
            )
            putInt(
                KEY_MOUSE_VERTICAL_SENSITIVITY,
                settings.mouseVerticalSensitivity.coerceIn(
                    MOUSE_VERTICAL_SENSITIVITY_MIN,
                    MOUSE_VERTICAL_SENSITIVITY_MAX
                )
            )
            putInt(
                KEY_MOUSE_SMOOTHING,
                settings.mouseSmoothing.coerceIn(MOUSE_SMOOTHING_MIN, MOUSE_SMOOTHING_MAX)
            )
            putInt(
                KEY_MOUSE_AIM_DEADZONE,
                settings.mouseAimDeadzone.coerceIn(MOUSE_DEADZONE_MIN, MOUSE_DEADZONE_MAX)
            )
            putBoolean(KEY_MOUSE_INVERT_X, settings.mouseInvertX)
            putBoolean(KEY_MOUSE_INVERT_Y, settings.mouseInvertY)
            putBoolean(KEY_MOUSE_CAPTURE_CURSOR, settings.mouseCaptureCursor)
            putBoolean(KEY_MOUSE_LEFT_HANDED, settings.mouseLeftHanded)
            putInt(
                KEY_MOUSE_WHEEL_MODE,
                settings.mouseWheelMode.coerceIn(MOUSE_WHEEL_OFF, MOUSE_WHEEL_SHOULDERS)
            )
            putInt(
                KEY_MOUSE_WHEEL_PULSE_MS,
                settings.mouseWheelPulseMs.coerceIn(
                    MOUSE_WHEEL_PULSE_MIN_MS,
                    MOUSE_WHEEL_PULSE_MAX_MS
                )
            )
        }
    }

    private fun encodeBindings(bindingsByPad: Map<Int, Map<String, Int>>): String {
        return org.json.JSONObject().apply {
            bindingsByPad.toSortedMap().forEach { (padIndex, bindings) ->
                if (bindings.isEmpty()) return@forEach
                put(
                    padIndex.toString(),
                    org.json.JSONObject().apply {
                        bindings.toSortedMap().forEach { (actionId, code) -> put(actionId, code) }
                    }
                )
            }
        }.toString()
    }

    private fun decodeBindings(raw: String?): Map<Int, Map<String, Int>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { org.json.JSONObject(raw) }.getOrNull()?.let { json ->
            buildMap {
                json.keys().forEach { padKey ->
                    val padIndex = padKey.toIntOrNull()?.coerceIn(0, 1) ?: return@forEach
                    val padObject = json.optJSONObject(padKey) ?: return@forEach
                    val bindings = buildMap {
                        padObject.keys().forEach { actionId ->
                            val code = padObject.optInt(actionId, Int.MIN_VALUE)
                            if (code != Int.MIN_VALUE) put(actionId, code)
                        }
                    }
                    if (bindings.isNotEmpty()) put(padIndex, bindings)
                }
            }
        }.orEmpty()
    }

    companion object {
        const val DEFAULT_MOUSE_SENSITIVITY = KeyboardMouseBindings.DEFAULT_MOUSE_SENSITIVITY
        const val MOUSE_SENSITIVITY_MIN = KeyboardMouseBindings.MOUSE_SENSITIVITY_MIN
        const val MOUSE_SENSITIVITY_MAX = KeyboardMouseBindings.MOUSE_SENSITIVITY_MAX
        const val DEFAULT_MOUSE_VERTICAL_SENSITIVITY =
            KeyboardMouseBindings.DEFAULT_MOUSE_VERTICAL_SENSITIVITY
        const val MOUSE_VERTICAL_SENSITIVITY_MIN =
            KeyboardMouseBindings.MOUSE_VERTICAL_SENSITIVITY_MIN
        const val MOUSE_VERTICAL_SENSITIVITY_MAX =
            KeyboardMouseBindings.MOUSE_VERTICAL_SENSITIVITY_MAX
        const val DEFAULT_MOUSE_SMOOTHING = 0
        const val MOUSE_SMOOTHING_MIN = 0
        const val MOUSE_SMOOTHING_MAX = 90
        const val DEFAULT_MOUSE_DEADZONE = KeyboardMouseBindings.DEFAULT_MOUSE_DEADZONE
        const val MOUSE_DEADZONE_MIN = KeyboardMouseBindings.MOUSE_DEADZONE_MIN
        const val MOUSE_DEADZONE_MAX = KeyboardMouseBindings.MOUSE_DEADZONE_MAX
        const val DEFAULT_MOUSE_WHEEL_PULSE_MS =
            KeyboardMouseBindings.DEFAULT_MOUSE_WHEEL_PULSE_MS
        const val MOUSE_WHEEL_PULSE_MIN_MS = KeyboardMouseBindings.MOUSE_WHEEL_PULSE_MIN_MS
        const val MOUSE_WHEEL_PULSE_MAX_MS = KeyboardMouseBindings.MOUSE_WHEEL_PULSE_MAX_MS
        const val MOUSE_AIM_OFF = KeyboardMouseBindings.MOUSE_AIM_OFF
        const val MOUSE_AIM_RIGHT_STICK = KeyboardMouseBindings.MOUSE_AIM_RIGHT_STICK
        const val MOUSE_AIM_LEFT_STICK = KeyboardMouseBindings.MOUSE_AIM_LEFT_STICK
        const val MOUSE_WHEEL_OFF = KeyboardMouseBindings.MOUSE_WHEEL_OFF
        const val MOUSE_WHEEL_DPAD = KeyboardMouseBindings.MOUSE_WHEEL_DPAD
        const val MOUSE_WHEEL_TRIGGERS = KeyboardMouseBindings.MOUSE_WHEEL_TRIGGERS
        const val MOUSE_WHEEL_LEFT_STICK = KeyboardMouseBindings.MOUSE_WHEEL_LEFT_STICK
        const val MOUSE_WHEEL_RIGHT_STICK = KeyboardMouseBindings.MOUSE_WHEEL_RIGHT_STICK
        const val MOUSE_WHEEL_SHOULDERS = KeyboardMouseBindings.MOUSE_WHEEL_SHOULDERS

        private const val KEY_KEYBOARD_ENABLED = "keyboard_enabled"
        private const val KEY_MOUSE_ENABLED = "mouse_enabled"
        private const val KEY_KEYBOARD_TARGET_PAD = "keyboard_target_pad"
        private const val KEY_MOUSE_TARGET_PAD = "mouse_target_pad"
        private const val KEY_KEYBOARD_BINDINGS = "keyboard_bindings"
        private const val KEY_MOUSE_BINDINGS = "mouse_bindings"
        private const val KEY_MOUSE_AIM_MODE = "mouse_aim_mode"
        private const val KEY_MOUSE_SENSITIVITY = "mouse_sensitivity"
        private const val KEY_MOUSE_VERTICAL_SENSITIVITY = "mouse_vertical_sensitivity"
        private const val KEY_MOUSE_SMOOTHING = "mouse_smoothing"
        private const val KEY_MOUSE_AIM_DEADZONE = "mouse_aim_deadzone"
        private const val KEY_MOUSE_INVERT_X = "mouse_invert_x"
        private const val KEY_MOUSE_INVERT_Y = "mouse_invert_y"
        private const val KEY_MOUSE_CAPTURE_CURSOR = "mouse_capture_cursor"
        private const val KEY_MOUSE_LEFT_HANDED = "mouse_left_handed"
        private const val KEY_MOUSE_WHEEL_MODE = "mouse_wheel_mode"
        private const val KEY_MOUSE_WHEEL_PULSE_MS = "mouse_wheel_pulse_ms"
        private val KEYBOARD_MOUSE_KEYS = setOf(
            KEY_KEYBOARD_ENABLED,
            KEY_MOUSE_ENABLED,
            KEY_KEYBOARD_TARGET_PAD,
            KEY_MOUSE_TARGET_PAD,
            KEY_KEYBOARD_BINDINGS,
            KEY_MOUSE_BINDINGS,
            KEY_MOUSE_AIM_MODE,
            KEY_MOUSE_SENSITIVITY,
            KEY_MOUSE_VERTICAL_SENSITIVITY,
            KEY_MOUSE_SMOOTHING,
            KEY_MOUSE_AIM_DEADZONE,
            KEY_MOUSE_INVERT_X,
            KEY_MOUSE_INVERT_Y,
            KEY_MOUSE_CAPTURE_CURSOR,
            KEY_MOUSE_LEFT_HANDED,
            KEY_MOUSE_WHEEL_MODE,
            KEY_MOUSE_WHEEL_PULSE_MS
        )
        private const val KEY_PACKAGES_FOLDER_URI = "packages_folder_uri"
        private const val KEY_PS3_STORAGE_ROOT_PATH = "ps3_storage_root_path"
        private const val KEY_GAME_DIRECTORIES = "game_directories"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_LAST_CORE_BINARY_FINGERPRINT = "last_core_binary_fingerprint"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_SKIPPED_UPDATE_TAG = "skipped_update_tag"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_SHOW_EMULATION_QUICK_BAR = "show_emulation_quick_bar"
    }
}
