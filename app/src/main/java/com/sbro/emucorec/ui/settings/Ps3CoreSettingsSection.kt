package com.sbro.emucorec.ui.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.sbro.emucorec.R
import com.sbro.emucorec.core.Ps3CoreSettingOverrides
import com.sbro.emucorec.core.CoreSettingsTreeCache
import com.sbro.emucorec.core.Ps3Runtime
import com.sbro.emucorec.ui.common.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpcsx.RPCSX
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

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
) {
    fun encodedValue(raw: String = value): String = when (type) {
        "bool", "int", "uint", "float" -> raw
        else -> JSONObject.quote(raw)
    }
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
    onOverridesChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val coroutineScope = rememberCoroutineScope()
    var settings by remember(scope, titleId) { mutableStateOf<List<Ps3CoreSetting>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(scope, titleId, revision) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                check(Ps3Runtime.ensureInitialized(context))
                val raw = CoreSettingsTreeCache.tree { RPCSX.instance.settingsGet("") }
                val gameOverrides = if (
                    scope == Ps3CoreSettingsScope.Game && !titleId.isNullOrBlank()
                ) {
                    Ps3CoreSettingOverrides.gameOverrides(context, titleId)
                } else {
                    emptyMap()
                }
                val globalValues = Ps3CoreSettingOverrides.resolvedGlobalValues(context)
                if (raw.isBlank()) emptyList() else buildList {
                    flattenCoreSettings(JSONObject(raw), "", "", this)
                }.map { setting ->
                    val defaultOverride = Ps3CoreSettingOverrides.RECOMMENDED_DEFAULTS[setting.path]
                    val adjustedSetting = if (defaultOverride != null) {
                        setting.copy(default = defaultOverride)
                    } else {
                        setting
                    }
                    gameOverrides[setting.path]?.let { encoded ->
                        adjustedSetting.copy(
                            value = decodeCoreValue(encoded),
                            overridden = true,
                        )
                    } ?: globalValues[setting.path]?.let { encoded ->
                        adjustedSetting.copy(value = decodeCoreValue(encoded))
                    } ?: adjustedSetting
                }
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
            val applied = runCatching { RPCSX.instance.settingsSet(setting.path, encoded) }
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
                    CoreSettingsTreeCache.invalidateTree()
                    onOverridesChanged()
                    revision++
                } else {
                    error = resources.getString(R.string.ps3_core_setting_rejected, setting.name)
                }
            }
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
                else error = resources.getString(R.string.ps3_core_setting_rejected, setting.name)
            }
        }
    }

    val filtered = remember(settings, category, additionalCategories, surface) {
        val accepted = additionalCategories + category
        settings.filter {
            coreSettingsCategory(it.path) in accepted &&
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
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        userFacingSettings.groupBy(Ps3CoreSetting::section).forEach { (section, sectionSettings) ->
            if (surface == Ps3CoreSettingsSurface.InGame) {
                InGameSettingsCard(title = localizedCoreLabel(section)) {
                    sectionSettings.forEach { setting ->
                        Ps3CoreSettingRow(
                            setting = setting,
                            onWrite = { write(setting, it) },
                            onReset = {
                                if (scope == Ps3CoreSettingsScope.Game) resetGameSetting(setting)
                                else write(setting, setting.default)
                            },
                        )
                    }
                }
            } else {
                SectionCard(
                    title = localizedCoreLabel(section),
                    contentPadding = PaddingValues(14.dp),
                ) {
                    sectionSettings.forEach { setting ->
                        Ps3CoreSettingRow(
                            setting = setting,
                            onWrite = { write(setting, it) },
                            onReset = {
                                if (scope == Ps3CoreSettingsScope.Game) resetGameSetting(setting)
                                else write(setting, setting.default)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * In-game settings card matching the emulation menu's look: the section title lives inside the
 * card, so each group is one clear block instead of a floating heading over a merged panel.
 */
@Composable
private fun InGameSettingsCard(title: String, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (dark) Color(0xF01A1F2A) else Color(0xFFFFFFFF),
        border = BorderStroke(1.dp, if (dark) Color.White.copy(alpha = 0.12f) else Color(0xFFD6DDE8)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = if (dark) Color.White else scheme.onSurface,
            )
            content()
        }
    }
}

internal fun coreSettingsCategory(path: String): Ps3CoreSettingsCategory {
    val root = path.substringBefore("@@")
    return when (root) {
        "System", "Miscellaneous" -> Ps3CoreSettingsCategory.General
        "Video" -> when {
            path.startsWith("Video@@Performance Overlay") ||
                path.startsWith("Video@@Debug overlay") ||
                path.startsWith("Video@@Shader Loading Dialog") -> Ps3CoreSettingsCategory.Overlay
            else -> Ps3CoreSettingsCategory.Graphics
        }
        "Audio" -> Ps3CoreSettingsCategory.Audio
        "Input/Output" -> Ps3CoreSettingsCategory.Controls
        "VFS", "Savestate" -> Ps3CoreSettingsCategory.Storage
        "Net" -> Ps3CoreSettingsCategory.Network
        else -> Ps3CoreSettingsCategory.Advanced
    }
}

/** Keeps each screen focused: global configuration, per-game compatibility, or live controls. */
internal fun isCoreSettingVisibleOnSurface(
    path: String,
    surface: Ps3CoreSettingsSurface,
): Boolean {
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
    "core_help_video_resolution_scale",
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
    onWrite: (String) -> Unit,
    onReset: () -> Unit,
) {
    val title = localizedCoreLabel(setting.name)
    val description = localizedCoreHelp(setting.path)
    when {
        setting.type == "bool" -> SettingToggleRow(
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
                    "0 (Auto)"
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
                    Slider(
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
            FilterChip(
                selected = isSelected,
                onClick = { onWrite(value) },
                colors = coreFilterChipColors(),
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
    else -> variants
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
        FilterChip(
            selected = true,
            onClick = { dialogVisible = true },
            colors = coreFilterChipColors(),
            label = {
                Text(
                    text = text.ifBlank { stringResource(R.string.core_setting_edit) },
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
                        Text(stringResource(R.string.core_setting_default_value, setting.default))
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
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
            shape = RoundedCornerShape(28.dp),
        )
    }
}

@Composable
private fun coreFilterChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    labelColor = MaterialTheme.colorScheme.onSurface,
    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
)

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
    if (resourceId == 0) return raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
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
            )
        }
    }
}
