package com.sbro.emucorec.ui.settings

import com.sbro.emucorec.ui.common.appSliderColors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Search
import com.sbro.emucorec.ui.common.AppAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sbro.emucorec.ui.common.appFilterChipColors
import com.sbro.emucorec.R
import com.sbro.emucorec.core.Ps3CoreSettingOverrides
import com.sbro.emucorec.core.MicrophoneDevices
import com.sbro.emucorec.core.CoreSettingsTreeCache
import com.sbro.emucorec.core.Ps3Runtime
import com.sbro.emucorec.core.Ps3LaunchBridge
import com.sbro.emucorec.core.EmulatorState
import com.sbro.emucorec.core.PRESENTATION_DEFAULTS
import com.sbro.emucorec.ui.common.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sbro.emucorec.core.Ps3Core
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong
import com.sbro.emucorec.ui.theme.neon.neonShape
import com.sbro.emucorec.ui.theme.neon.neonButtonShape
import com.sbro.emucorec.ui.theme.neon.neonChipShape

enum class Ps3CoreSettingsScope { Global, Game }

enum class Ps3CoreSettingsSurface { FullSettings, GameProfile, InGame }

enum class Ps3CoreSettingsCategory {
    General,
    Graphics,
    Overlay,
    Audio,
    Controls,
    Storage,
    Network,
    Advanced,
}

internal data class CoreSettingEditState(val value: String, val overridden: Boolean, val pendingRestart: Boolean)

internal fun resolveCoreSettingEditState(
    path: String,
    liveValue: String,
    dynamic: Boolean,
    globalValues: Map<String, String>,
    gameValues: Map<String, String>,
    savedGlobalPaths: Set<String>,
): CoreSettingEditState {
    val encoded = gameValues[path] ?: globalValues[path]
    val desired = encoded?.let(::decodeCoreValue) ?: liveValue
    return CoreSettingEditState(
        value = desired,
        overridden = path in gameValues,
        pendingRestart = (path in gameValues || path in savedGlobalPaths) && !dynamic && desired != liveValue,
    )
}

private data class Ps3CoreSetting(
    val path: String,
    val name: String,
    val section: String,
    val type: String,
    val value: String,
    val default: String,
    val variants: List<String>,
    val min: Double?,
    val max: Double?,
    val overridden: Boolean = false,
    val dynamic: Boolean = true,
    val liveValue: String = value,
    val pendingRestart: Boolean = false,
) {
    fun encodedValue(raw: String = value): String = when (type) {
        "bool", "int", "uint", "float" -> raw
        else -> JSONObject.quote(raw)
    }
}


/** Prepared once per native snapshot, shared across categories and screens. */
private object CoreSettingsUiCache {
    @Volatile private var catalog: List<Ps3CoreSetting> = emptyList()
    private var catalogJson: String? = null

    fun peek(context: Context, scope: Ps3CoreSettingsScope, titleId: String?): List<Ps3CoreSetting> =
        resolve(context, scope, titleId, catalog)

    @Synchronized
    fun load(context: Context, scope: Ps3CoreSettingsScope, titleId: String?): List<Ps3CoreSetting> {
        val raw = CoreSettingsTreeCache.tree { Ps3Core.instance.settingsGet("") }
        if (raw != catalogJson) {
            val parsed = if (raw.isBlank()) emptyList() else buildList {
                flattenCoreSettings(JSONObject(raw), "", "", this)
                PRESENTATION_DEFAULTS.forEach { (path, value) ->
                    val bounds = if (path == "Video@@VR Picture Size") 20.0 to 100.0 else -100.0 to 100.0
                    add(Ps3CoreSetting(
                        path, path.substringAfter("@@"), "Video", if (value == "false") "bool" else "int",
                        value, value, emptyList(), if (value == "false") null else bounds.first,
                        if (value == "false") null else bounds.second,
                    ))
                }
            }
            // Reflection-backed resource lookup belongs on this worker, not in the
            // first frame of every category after a tab switch.
            parsed.forEach { setting ->
                val names = listOf(coreHelpResourceName(setting.path), coreLabelResourceName(setting.name),
                    coreLabelResourceName(setting.section)) + setting.variants.map(::coreLabelResourceName)
                names.forEach { name ->
                    CoreSettingsTreeCache.resourceId(name) {
                        context.resources.getIdentifier(name, "string", context.packageName)
                    }
                }
            }
            catalog = parsed
            catalogJson = raw
        }
        return resolve(context, scope, titleId, catalog)
    }

    private fun resolve(
        context: Context,
        scope: Ps3CoreSettingsScope,
        titleId: String?,
        base: List<Ps3CoreSetting>,
    ): List<Ps3CoreSetting> {
        if (base.isEmpty()) return emptyList()
        val gameOverrides = if (scope == Ps3CoreSettingsScope.Game && !titleId.isNullOrBlank()) {
            Ps3CoreSettingOverrides.gameOverrides(context, titleId)
        } else emptyMap()
        val globalValues = Ps3CoreSettingOverrides.resolvedGlobalValues(context)
        val savedGlobalValues = Ps3CoreSettingOverrides.savedGlobalValues(context)
        return base.map { setting ->
            val defaultOverride = Ps3CoreSettingOverrides.RECOMMENDED_DEFAULTS[setting.path]
            val adjusted = if (defaultOverride != null) setting.copy(default = defaultOverride) else setting
            val edit = resolveCoreSettingEditState(
                setting.path, setting.liveValue, setting.dynamic, globalValues,
                gameOverrides, savedGlobalValues.keys,
            )
            adjusted.copy(value = edit.value, overridden = edit.overridden, pendingRestart = edit.pendingRestart)
        }
    }
}

/** Called on a worker while the library is opening, before a settings tab is needed. */
internal fun preloadCoreSettings(context: Context) {
    if (Ps3Core.initialized) CoreSettingsUiCache.load(context.applicationContext, Ps3CoreSettingsScope.Global, null)
}

/** Renders one logical slice of the live RPCS3 config tree inside EmuCoreC's own tabs. */
@Composable
fun Ps3CoreSettingsSection(
    category: Ps3CoreSettingsCategory,
    scope: Ps3CoreSettingsScope,
    modifier: Modifier = Modifier,
    titleId: String? = null,
    additionalCategories: Set<Ps3CoreSettingsCategory> = emptySet(),
    surface: Ps3CoreSettingsSurface = Ps3CoreSettingsSurface.FullSettings,
    hiddenSections: Set<String> = emptySet(),
    onOverridesChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val coroutineScope = rememberCoroutineScope()
    var settings by remember(scope, titleId) {
        mutableStateOf(CoreSettingsUiCache.peek(context, scope, titleId))
    }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var restarting by remember { mutableStateOf(false) }

    fun settingTitle(setting: Ps3CoreSetting): String {
        // The audio backend setting is also called "Renderer" in the core metadata, which reads as
        // the graphics renderer inside the audio tab. Give it its own label.
        val raw = if (setting.path.startsWith("Audio@@") && setting.name == "Renderer") {
            "Audio Renderer"
        } else {
            setting.name
        }
        val titleId = resources.getIdentifier(coreLabelResourceName(raw), "string", context.packageName)
        return resources.getString(if (titleId != 0) titleId else R.string.core_label_unknown)
    }

    LaunchedEffect(scope, titleId, revision) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                check(Ps3Runtime.ensureInitialized(context))
                CoreSettingsUiCache.load(context, scope, titleId)
            }
        }
        result.onSuccess {
            settings = it
            error = if (it.isEmpty()) resources.getString(R.string.ps3_core_settings_unavailable) else null
        }.onFailure {
            error = resources.getString(R.string.ps3_core_settings_unavailable)
        }
    }

    fun write(setting: Ps3CoreSetting, raw: String) {
        coroutineScope.launch(Dispatchers.IO) {
            val encoded = setting.encodedValue(raw)
            val applied = runCatching {
                if (setting.path in PRESENTATION_DEFAULTS) {
                    Ps3CoreSettingOverrides.validPresentationEdit(setting.path, encoded)
                } else if (scope == Ps3CoreSettingsScope.Game && !titleId.isNullOrBlank()) {
                    Ps3Core.instance.settingsSetTransient(setting.path, encoded)
                } else {
                    Ps3Core.instance.settingsSet(setting.path, encoded)
                }
            }
                .getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (applied) {
                    if (scope == Ps3CoreSettingsScope.Game && !titleId.isNullOrBlank()) {
                        Ps3CoreSettingOverrides.recordGame(
                            context,
                            titleId,
                            setting.path,
                            encoded,
                            setting.encodedValue(),
                        )
                    } else {
                        Ps3CoreSettingOverrides.recordGlobal(context, setting.path, encoded)
                    }
                    if (setting.path in PRESENTATION_DEFAULTS) {
                        Ps3CoreSettingOverrides.applyPresentationSettings(
                            context, titleId.takeIf { scope == Ps3CoreSettingsScope.Game },
                        )
                    }
                    CoreSettingsTreeCache.invalidateTree()
                    onOverridesChanged()
                    revision++
                } else {
                    error = resources.getString(R.string.ps3_core_setting_rejected, settingTitle(setting))
                }
            }
        }
    }

    var pendingMicrophoneChange by remember { mutableStateOf<Pair<Ps3CoreSetting, String>?>(null) }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingMicrophoneChange?.let { (setting, value) ->
            if (granted) write(setting, value)
            else error = resources.getString(R.string.ps3_core_setting_rejected, settingTitle(setting))
        }
        pendingMicrophoneChange = null
    }

    fun writeWithPermission(setting: Ps3CoreSetting, raw: String) {
        if (setting.path == "Audio@@Microphone Type" && raw != "Null" &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingMicrophoneChange = setting to raw
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            write(setting, raw)
        }
    }

    fun resetGameSetting(setting: Ps3CoreSetting) {
        val gameId = titleId?.takeIf(String::isNotBlank) ?: return
        coroutineScope.launch(Dispatchers.IO) {
            val restored = Ps3CoreSettingOverrides.clearGameSetting(
                context = context,
                titleId = gameId,
                path = setting.path,
                coreDefaultEncodedValue = setting.encodedValue(setting.default),
            )
            withContext(Dispatchers.Main) {
                if (restored) {
                    CoreSettingsTreeCache.invalidateTree()
                    onOverridesChanged()
                    revision++
                }
                else error = resources.getString(R.string.ps3_core_setting_rejected, settingTitle(setting))
            }
        }
    }

    val filtered = remember(settings, category, additionalCategories, surface, hiddenSections) {
        val accepted = additionalCategories + category
        settings.distinctBy(Ps3CoreSetting::path).filter {
            coreSettingsCategory(it.path) in accepted &&
                it.section !in hiddenSections &&
                isCoreSettingVisibleOnSurface(it.path, surface)
        }
    }
    val userFacingSettings = remember(filtered) {
        filtered.filter { setting ->
            CoreSettingsTreeCache.resourceId(coreHelpResourceName(setting.path)) {
                resources.getIdentifier(
                    coreHelpResourceName(setting.path),
                    "string",
                    context.packageName,
                )
            } != 0
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (settings.isEmpty() && error == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (userFacingSettings.any { it.pendingRestart } &&
            Ps3Runtime.state() != EmulatorState.Stopped
        ) {
            // The button already names the outcome, so the caption that used to sit above it
            // is gone; it inherited the menu's unset content colour and read as dark-on-dark.
            // The action itself spans the content width instead of floating inset and pill-sized.
            Button(
                enabled = !restarting,
                onClick = {
                    restarting = true
                    coroutineScope.launch {
                        if (!Ps3LaunchBridge.restartRunningGame(context)) {
                            error = resources.getString(R.string.game_launch_failed)
                            restarting = false
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = neonButtonShape(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.ps3_core_apply_restart),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
        userFacingSettings.groupBy { coreSettingsGroup(it.path) }
            .toSortedMap(compareBy(CoreSettingsGroup::ordinal)).forEach { (group, groupSettings) ->
            val sectionSettings = groupSettings.sortedWith(compareBy({ coreSettingOrder(it.path) }, Ps3CoreSetting::path))
            if (surface == Ps3CoreSettingsSurface.InGame) {
                // In-game the tabs already name the area and every row carries its own title,
                // so the section labels were repeated noise and are not rendered at all.
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    sectionSettings.forEach { setting ->
                        Ps3CoreSettingRow(
                            setting = setting,
                            inGame = true,
                            onWrite = { writeWithPermission(setting, it) },
                            onReset = {
                                if (scope == Ps3CoreSettingsScope.Game) resetGameSetting(setting)
                                else write(setting, setting.default)
                            },
                        )
                    }
                }
            } else {
                SectionCard(
                    title = stringResource(group.titleRes),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    sectionSettings.forEach { setting ->
                        Ps3CoreSettingRow(
                            setting = setting,
                            inGame = surface == Ps3CoreSettingsSurface.InGame,
                            onWrite = { writeWithPermission(setting, it) },
                            onReset = {
                                if (scope == Ps3CoreSettingsScope.Game) resetGameSetting(setting)
                                else write(setting, setting.default)
                            },
                        )
                    }
                    if (group == CoreSettingsGroup.Microphone && surface == Ps3CoreSettingsSurface.FullSettings) {
                        val device = sectionSettings.firstOrNull { it.path == "Audio@@Microphone Devices" }
                            ?.value?.substringBefore("@@@")?.ifBlank { "Default" } ?: "Default"
                        MicrophoneTest(device)
                    }
                }
            }
        }
    }
}

@Composable
private fun MicrophoneTest(device: String) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var startJob by remember { mutableStateOf<Job?>(null) }

    fun start() {
        if (startJob?.isActive == true) return
        startJob = scope.launch {
            failed = false
            val opened = withContext(Dispatchers.IO) { runCatching { Ps3Core.instance.micTestStart(device) }.getOrDefault(false) }
            running = opened
            failed = !opened
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else failed = true
    }
    LaunchedEffect(running) {
        while (running) {
            val next = withContext(Dispatchers.IO) { runCatching { Ps3Core.instance.micTestLevel() }.getOrDefault(-1f) }
            if (next < 0f) running = false else level = next.coerceIn(0f, 1f)
            delay(100)
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                startJob?.cancel()
                runCatching { Ps3Core.instance.micTestStop() }
                running = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            startJob?.cancel()
            runCatching { Ps3Core.instance.micTestStop() }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            if (running) {
                runCatching { Ps3Core.instance.micTestStop() }
                running = false
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                start()
            } else permission.launch(Manifest.permission.RECORD_AUDIO)
        }) {
            Text(stringResource(if (running) R.string.common_cancel else R.string.settings_test_vibration_button))
        }
        if (running) LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
        if (failed) Text(stringResource(R.string.ps3_core_setting_rejected, localizedCoreLabel("Microphone Devices")), color = MaterialTheme.colorScheme.error)
    }
}

/** Keeps each screen focused: global configuration, per-game compatibility, or live controls. */
internal fun isCoreSettingVisibleOnSurface(
    path: String,
    surface: Ps3CoreSettingsSurface,
): Boolean {
    if (!isAndroidCoreSettingSupported(path)) return false
    val key = coreHelpResourceName(path)
    return when (surface) {
        Ps3CoreSettingsSurface.FullSettings -> true

        Ps3CoreSettingsSurface.GameProfile -> when {
            path.startsWith("VFS@@") || path.startsWith("Savestate@@") -> false
            path.startsWith("Miscellaneous@@") -> false
            path.startsWith("System@@") -> key in GAME_PROFILE_SYSTEM_KEYS
            path.startsWith("Net@@") -> key in GAME_PROFILE_NETWORK_KEYS
            key in GAME_PROFILE_GLOBAL_ONLY_KEYS -> false
            else -> true
        }

        // Only player-facing controls belong here. Compatibility/debugging and device
        // configuration remain available in full settings and the game profile.
        Ps3CoreSettingsSurface.InGame -> key in IN_GAME_SETTING_KEYS
    }
}

private val GAME_PROFILE_SYSTEM_KEYS = setOf(
    "core_help_system_language",
    "core_help_system_license_area",
    "core_help_system_enter_button_assignment",
)

private val GAME_PROFILE_GLOBAL_ONLY_KEYS = setOf(
    "core_help_video_vulkan_adapter",
    "core_help_audio_renderer",
    "core_help_audio_audio_device",
    "core_help_audio_microphone_devices",
    "core_help_input_output_background_input_enabled",
    "core_help_input_output_load_sdl_gamecontroller_mappings",
    "core_help_input_output_pad_handler_mode",
)

private val GAME_PROFILE_NETWORK_KEYS = setOf(
    "core_help_net_internet_enabled",
    "core_help_net_psn_status",
    "core_help_net_psn_country",
    "core_help_net_clans_enabled",
)

// Ranges for RPCS3 numeric settings whose min/max are not exposed by the core
// JSON; lets the app render them as sliders instead of free-text inputs.
private val CORE_NUMERIC_RANGES: Map<String, Pair<Double, Double>> = mapOf(
    "Core@@PPU Threads" to (1.0 to 8.0),
    "Core@@Max LLVM Compile Threads" to (0.0 to 1024.0),
)

private fun coreNumericRange(setting: Ps3CoreSetting): Pair<Double, Double>? =
    setting.min?.let { min -> setting.max?.let { max -> min to max } }
        ?: CORE_NUMERIC_RANGES[setting.path]

private val IN_GAME_SETTING_KEYS = setOf(
    "core_help_video_resolution",
    "core_help_video_resolution_scale",
    "core_help_video_msaa",
    "core_help_video_write_color_buffers",
    "core_help_video_read_color_buffers",
    "core_help_video_write_depth_buffer",
    "core_help_video_read_depth_buffer",
    "core_help_video_output_scaling_mode",
    "core_help_video_fidelityfx_cas_sharpening_intensity",
    "core_help_video_aspect_ratio",
    "core_help_video_stretch_to_display_area",
    "core_help_video_frame_limit",
    "core_help_video_vsync_mode",
    "core_help_video_anisotropic_filter_override",
    "core_help_video_performance_overlay_enabled",
    "core_help_video_performance_overlay_show_header",
    "core_help_video_performance_overlay_position",
    "core_help_video_performance_overlay_detail_level",
    "core_help_video_performance_overlay_font_size_px",
    "core_help_video_performance_overlay_opacity",
    "core_help_audio_master_volume",
    "core_help_audio_enable_buffering",
    "core_help_audio_desired_audio_buffer_duration",
    "core_help_audio_enable_time_stretching",
    "core_help_audio_time_stretching_threshold",
)

@Composable
private fun Ps3CoreSettingRow(
    setting: Ps3CoreSetting,
    inGame: Boolean = false,
    onWrite: (String) -> Unit,
    onReset: () -> Unit,
) {
    val title = localizedCoreLabel(if (setting.path == "Audio@@Renderer") "Audio Renderer" else setting.name)
    val description = localizedCoreHelp(setting.path)
    when {
        setting.path == "Audio@@Microphone Devices" -> MicrophoneDeviceRows(setting, title, description, onWrite, onReset)

        setting.type == "bool" -> SettingToggleRow(
            compact = inGame,
            title = title,
            description = description,
            checked = setting.value == "true",
            onCheckedChange = { onWrite(it.toString()) },
            onResetDefault = onReset,
        )

        setting.variants.isNotEmpty() -> CoreEnumRow(setting, title, description, onWrite, onReset)

        setting.type in setOf("int", "uint", "float") -> {
            val range = coreNumericRange(setting)
            // Everything numeric gets a slider on touch devices; only absurdly
            // wide ranges (millions) fall back to manual input.
            if (range != null && range.second > range.first && range.second - range.first <= 5_000_000.0) {
                val value = (setting.value.toDoubleOrNull() ?: range.first).coerceIn(range.first, range.second)
                var sliderValue by remember(setting.path, setting.value) {
                    mutableFloatStateOf(value.toFloat())
                }
                val displayValue = if (setting.path == "Core@@Max LLVM Compile Threads" && sliderValue.toInt() == 0) {
                    "0 (${localizedCoreLabel("Automatic")})"
                } else {
                    formatCoreNumber(setting.type, sliderValue)
                }
                val isIntegerStep = setting.type in setOf("int", "uint") && (range.second - range.first) <= 64
                SettingSliderRow(
                    title = title,
                    description = description,
                    valueText = displayValue,
                    onResetDefault = onReset,
                ) {
                    Slider(colors = appSliderColors(),
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        onValueChangeFinished = { onWrite(formatCoreNumber(setting.type, sliderValue)) },
                        valueRange = range.first.toFloat()..range.second.toFloat(),
                        steps = if (isIntegerStep) ((range.second - range.first).toInt() - 1).coerceAtLeast(0) else 0,
                    )
                }
            } else {
                CoreTextRow(setting, title, description, onWrite, onReset)
            }
        }

        else -> CoreTextRow(setting, title, description, onWrite, onReset)
    }
}

@Composable
private fun MicrophoneDeviceRows(setting: Ps3CoreSetting, title: String, description: String, onWrite: (String) -> Unit, onReset: () -> Unit) {
    val available by MicrophoneDevices.names.collectAsState()
    val slots = remember(setting.value) { setting.value.split("@@@").toMutableList().apply { while (size < 4) add("") } }
    for (index in 0..1) {
        SettingChoiceRow(title = "$title ${index + 1}", description = description, onResetDefault = onReset) {
            val choices = listOf(if (index == 0) "Default" else "") + available
            choices.forEach { name ->
                val label = if (name == "Default") localizedCoreLabel("Automatic") else if (name.isEmpty()) localizedCoreLabel("None") else name
                val selected = slots[index] == name || (index == 0 && slots[index].isEmpty() && name == "Default")
                FilterChip(
                    shape = neonChipShape(), selected = selected,
                    onClick = {
                        val updated = slots.toMutableList()
                        updated[index] = name
                        if (index == 1 && updated[0].isEmpty()) updated[0] = "Default"
                        onWrite(updated.joinToString("@@@"))
                    },
                    colors = appFilterChipColors(),
                    label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

@Composable
private fun CoreEnumRow(
    setting: Ps3CoreSetting,
    title: String,
    description: String,
    onWrite: (String) -> Unit,
    onReset: () -> Unit,
) {
    val visibleVariants = remember(setting.path, setting.variants) {
        userFacingCoreVariants(setting.path, setting.variants)
    }

    SettingChoiceRow(
        title = title,
        description = description,
        onResetDefault = onReset,
    ) {
        visibleVariants.forEach { value ->
            val label = localizedCoreLabel(value)
            val isSelected = setting.value == value
            FilterChip(shape = neonChipShape(),
                selected = isSelected,
                onClick = { onWrite(value) },
                colors = appFilterChipColors(),
                label = {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

internal fun userFacingCoreVariants(path: String, variants: List<String>): List<String> = when (path) {
    // Null produces no image. Vulkan and the Android OpenGL ES compatibility
    // backend are both compiled and may be selected by the player.
    "Video@@Renderer" -> variants.filterNot {
        it.equals("Null", ignoreCase = true)
    }
    // Android has a fake cellCamera device but no Qt/SDL camera backend.
    "Input/Output@@Camera" -> variants.filter { it == "Null" || it == "Fake" }
    else -> variants
}

@Composable
private fun localizedCoreTextValue(path: String, value: String): String = when {
    value.isBlank() -> stringResource(R.string.core_setting_edit)
    (path == "Audio@@Audio Device" && value == "@@@default@@@") ||
        (path == "Input/Output@@Camera ID" && value == "Default") -> localizedCoreLabel("Automatic")
    else -> value
}

@Composable
private fun CoreTextRow(
    setting: Ps3CoreSetting,
    title: String,
    description: String,
    onWrite: (String) -> Unit,
    onReset: () -> Unit,
) {
    var dialogVisible by remember(setting.path) { mutableStateOf(false) }
    var text by remember(setting.path, setting.value) { mutableStateOf(setting.value) }
    SettingChoiceRow(
        title = title,
        description = description,
        onResetDefault = onReset,
    ) {
        FilterChip(shape = neonChipShape(),
            selected = true,
            onClick = { dialogVisible = true },
            colors = appFilterChipColors(),
            label = {
                Text(
                    text = localizedCoreTextValue(setting.path, text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }
    if (dialogVisible) {
        AlertDialog(
            onDismissRequest = { dialogVisible = false; text = setting.value },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.core_setting_value)) },
                    supportingText = {
                        Text(stringResource(R.string.core_setting_default_value, localizedCoreTextValue(setting.path, setting.default)))
                    },
                    singleLine = true,
                    shape = neonShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { dialogVisible = false; onWrite(text) }) {
                    Text(stringResource(R.string.core_setting_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogVisible = false; text = setting.value }) {
                    Text(stringResource(R.string.core_setting_cancel))
                }
            },
            shape = neonShape(28.dp),
        )
    }
}


@Composable
private fun localizedCoreLabel(raw: String): String {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = resources.configuration.locales[0]
    val resourceName = remember(raw) { coreLabelResourceName(raw) }
    val resourceId = remember(resourceName, resources) {
        CoreSettingsTreeCache.resourceId(resourceName) {
            resources.getIdentifier(resourceName, "string", context.packageName)
        }
    }
    if (resourceId == 0) return stringResource(R.string.core_label_unknown)
    val localized = stringResource(resourceId)
    return remember(localized, locale) {
        localized.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}

@Composable
private fun localizedCoreHelp(path: String): String {
    val context = LocalContext.current
    val resources = LocalResources.current
    val resourceName = remember(path) { coreHelpResourceName(path) }
    val resourceId = remember(resourceName, resources) {
        CoreSettingsTreeCache.resourceId(resourceName) {
            resources.getIdentifier(resourceName, "string", context.packageName)
        }
    }
    check(resourceId != 0) { "Missing localized RPCS3 help for $path" }
    return stringResource(resourceId)
}

internal fun coreLabelResourceName(raw: String): String {
    val slug = coreResourceSlug(raw)
    return "core_label_$slug"
}

internal fun coreHelpResourceName(path: String): String {
    return "core_help_${coreResourceSlug(path)}"
}

private fun coreResourceSlug(raw: String): String =
    raw.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

private fun formatCoreNumber(type: String, value: Float): String = when (type) {
    "float" -> value.toString().trimEnd('0').trimEnd('.').ifEmpty { "0" }
    else -> value.roundToLong().toString()
}

private fun decodeCoreValue(encoded: String): String {
    if (!encoded.trimStart().startsWith('"')) return encoded
    return runCatching { JSONObject("{\"value\":$encoded}").getString("value") }
        .getOrDefault(encoded.trim('"'))
}

private fun flattenCoreSettings(
    node: JSONObject,
    prefix: String,
    section: String,
    out: MutableList<Ps3CoreSetting>,
) {
    node.keys().forEach { key ->
        val child = node.optJSONObject(key) ?: return@forEach
        val type = child.optString("type", "")
        val path = if (prefix.isEmpty()) key else "$prefix@@$key"
        if (type.isEmpty()) {
            flattenCoreSettings(child, path, key, out)
        } else {
            val variants = child.optJSONArray("variants")?.let { array ->
                List(array.length()) { array.optString(it) }
            }.orEmpty()
            out += Ps3CoreSetting(
                path = path,
                name = key,
                section = section.ifEmpty { key },
                type = type,
                value = if (type == "bool") child.optBoolean("value").toString() else child.optString("value"),
                default = if (type == "bool") child.optBoolean("default").toString() else child.optString("default"),
                variants = variants,
                min = child.optString("min").toDoubleOrNull(),
                max = child.optString("max").toDoubleOrNull(),
                dynamic = child.optBoolean("dynamic", true),
            )
        }
    }
}
