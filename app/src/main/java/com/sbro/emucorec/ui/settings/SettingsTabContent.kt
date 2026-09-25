package com.sbro.emucorec.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.StayCurrentPortrait
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.view.InputDevice
import android.widget.Toast
import com.sbro.emucorec.R
import com.sbro.emucorec.core.AndroidGyroscopeInput
import com.sbro.emucorec.core.GpuDriverCompatibility
import com.sbro.emucorec.core.InstalledGpuDriver
import com.sbro.emucorec.core.Ps3CoreConfig
import com.sbro.emucorec.core.Ps3StorageLocation
import com.sbro.emucorec.core.input.InputDeviceClassifier
import com.sbro.emucorec.data.AppLanguage
import com.sbro.emucorec.ui.common.SectionCard
import com.sbro.emucorec.ui.theme.ScreenHorizontalPadding
import kotlin.math.roundToInt
import com.sbro.emucorec.ui.theme.neon.neonShape
import com.sbro.emucorec.ui.theme.neon.neonChipShape
import com.sbro.emucorec.ui.theme.neon.neonButtonShape

private val SettingsSectionContentPadding = 14.dp
private val SettingsSectionRowPadding = ScreenHorizontalPadding
private val SettingsCardInnerPadding = 14.dp
private const val EmuCoreRepositoryUrl = "https://github.com/sashkinbro/EmuCoreC"
private const val EmuCoreWebsiteUrl = "https://emucorec.web.app"
private const val EmuCoreSupportUrl = "https://www.patreon.com/c/emucore/membership"
private const val SashkinAppsPlayStoreUrl = "https://play.google.com/store/apps/dev?id=7136622298887775989"
private const val RPCS3RepositoryUrl = "https://github.com/RPCS3/rpcs3"
private const val PrivacyPolicyUrl = "https://sites.google.com/view/privacy-policy-for-emucorec/%D0%B3%D0%BE%D0%BB%D0%BE%D0%B2%D0%BD%D0%B0-%D1%81%D1%82%D0%BE%D1%80%D1%96%D0%BD%D0%BA%D0%B0"

@Composable
fun SettingsTabContent(
    selectedTab: SettingsTab,
    uiState: SettingsUiState,
    defaults: Ps3CoreConfig,
    viewModel: SettingsViewModel,
    onOpenLanguageSettings: () -> Unit,
    onOpenGpuDriverSettings: () -> Unit = {},
    onOpenTouchControlsEditor: () -> Unit = {},
    onAddGameFolder: () -> Unit = {},
    createBackupClick: () -> Unit,
    restoreBackupClick: () -> Unit
) {
    when (selectedTab) {
        SettingsTab.General -> {
            GeneralTab(uiState, viewModel, onOpenLanguageSettings)
            Ps3CoreSettingsSection(
                category = Ps3CoreSettingsCategory.General,
                scope = Ps3CoreSettingsScope.Global,
            )
        }
        SettingsTab.Customization -> CustomizationTab(uiState.customization, viewModel)
        SettingsTab.Graphics -> {
            GraphicsTab(uiState, defaults, viewModel, onOpenGpuDriverSettings)
            Ps3CoreSettingsSection(Ps3CoreSettingsCategory.Graphics, scope = Ps3CoreSettingsScope.Global)
        }
        SettingsTab.Overlay -> Ps3CoreSettingsSection(Ps3CoreSettingsCategory.Overlay, scope = Ps3CoreSettingsScope.Global)
        SettingsTab.Audio -> Ps3CoreSettingsSection(Ps3CoreSettingsCategory.Audio, scope = Ps3CoreSettingsScope.Global)
        SettingsTab.Controls -> {
            ControlsTab(
                uiState = uiState,
                defaults = defaults,
                viewModel = viewModel,
                onOpenTouchControlsEditor = onOpenTouchControlsEditor
            )
            Ps3CoreSettingsSection(Ps3CoreSettingsCategory.Controls, scope = Ps3CoreSettingsScope.Global)
        }
        SettingsTab.Storage -> {
            StorageTab(
                uiState = uiState,
                viewModel = viewModel,
                selectStorageLocation = viewModel::selectStorageLocation,
                dismissStorageMigrationDialog = viewModel::dismissStorageMigrationDialog,
                onAddGameFolder = onAddGameFolder,
                createBackupClick = createBackupClick,
                restoreBackupClick = restoreBackupClick
            )
            Ps3CoreSettingsSection(Ps3CoreSettingsCategory.Storage, scope = Ps3CoreSettingsScope.Global)
        }
        SettingsTab.Network -> Ps3CoreSettingsSection(Ps3CoreSettingsCategory.Network, scope = Ps3CoreSettingsScope.Global)
        SettingsTab.Advanced -> Ps3CoreSettingsSection(Ps3CoreSettingsCategory.Advanced, scope = Ps3CoreSettingsScope.Global)
        SettingsTab.About -> AboutTab()
        SettingsTab.Updates -> AppUpdateTab(
            state = uiState.appUpdate,
            onLoadReleaseHistory = { forceRefresh ->
                viewModel.loadAppReleaseHistory(showErrors = true, forceRefresh = forceRefresh)
            }
        )
    }
}

@Composable
private fun GeneralTab(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    onOpenLanguageSettings: () -> Unit
) {
    SectionCard(title = stringResource(R.string.settings_tab_general), contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsSectionContentPadding)) {
        AppLanguageSettingRow(
            selectedLanguage = uiState.appLanguage,
            onClick = onOpenLanguageSettings
        )
        KeepScreenOnSettingRow(
            keepScreenOn = uiState.keepScreenOn,
            onCheckedChange = viewModel::updateKeepScreenOn
        )
    }

}

@Composable
private fun GraphicsTab(uiState: SettingsUiState, defaults: Ps3CoreConfig, viewModel: SettingsViewModel, onOpenGpuDriverSettings: () -> Unit) {
    val selectedDriver = uiState.installedGpuDrivers.firstOrNull { it.name == uiState.coreConfig.customDriverName }
    val customDriversSupported = remember { GpuDriverCompatibility.supportsAdrenoToolsCustomDrivers() }

    SectionCard(title = stringResource(R.string.settings_tab_graphics), contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsSectionContentPadding)) {
        if (customDriversSupported) {
            Chips(
                title = stringResource(R.string.settings_gpu_driver),
                description = stringResource(R.string.settings_help_gpu_driver),
                onResetDefault = { viewModel.updateCoreSettings { it.copy(customDriverName = defaults.customDriverName) } }
            ) {
                FilterChip(shape = neonChipShape(), 
                    selected = uiState.coreConfig.customDriverName.isBlank(),
                    onClick = { viewModel.updateCoreSettings { it.copy(customDriverName = "") } },
                    label = { Text(stringResource(R.string.settings_gpu_driver_system)) }
                )
                FilterChip(shape = neonChipShape(), 
                    selected = uiState.coreConfig.customDriverName.isNotBlank(),
                    onClick = {
                        val firstDriver = uiState.installedGpuDrivers.firstOrNull()
                        if (firstDriver != null) {
                            viewModel.updateCoreSettings { it.copy(customDriverName = firstDriver.name) }
                        }
                    },
                    enabled = uiState.installedGpuDrivers.isNotEmpty(),
                    label = { Text(stringResource(R.string.settings_gpu_driver_custom)) }
                )
            }
            GpuDriverStatus(
                selectedDriver = selectedDriver,
                modifier = Modifier.padding(horizontal = SettingsSectionRowPadding)
            )
            Button(shape = neonButtonShape(), 
                onClick = onOpenGpuDriverSettings,
                modifier = Modifier.padding(horizontal = SettingsSectionRowPadding)
            ) {
                Text(stringResource(R.string.settings_gpu_driver_manage))
            }
        }
        Text(
            text = stringResource(R.string.settings_gpu_driver_manager_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SettingsSectionRowPadding)
        )
    }
}

@Composable
private fun GpuDriverStatus(
    selectedDriver: InstalledGpuDriver?,
    modifier: Modifier = Modifier
) {
    val text = when {
        selectedDriver == null -> stringResource(R.string.settings_gpu_driver_status_system)
        !selectedDriver.isUsable -> stringResource(R.string.settings_gpu_driver_status_broken, selectedDriver.name)
        else -> stringResource(R.string.settings_gpu_driver_status_active, selectedDriver.name)
    }
    val supporting = when {
        selectedDriver == null -> stringResource(R.string.settings_gpu_driver_status_system_desc)
        !selectedDriver.isUsable -> stringResource(R.string.settings_gpu_driver_status_broken_desc)
        else -> stringResource(R.string.settings_gpu_driver_status_active_desc)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
    ) {
        Column(modifier = Modifier.padding(horizontal = SettingsCardInnerPadding, vertical = SettingsCardInnerPadding)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ControlsTab(
    uiState: SettingsUiState,
    defaults: Ps3CoreConfig,
    viewModel: SettingsViewModel,
    onOpenTouchControlsEditor: () -> Unit
) {
    val context = LocalContext.current
    val gamepadConnected = remember {
        InputDevice.getDeviceIds().any { id ->
            InputDeviceClassifier.isPhysicalGameController(id)
        }
    }
    SectionCard(title = stringResource(R.string.settings_tab_controls), contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsSectionContentPadding)) {
        SliderRow(stringResource(R.string.settings_core_analog_multiplier_label), stringResource(R.string.settings_help_analog_multiplier), stringResource(R.string.settings_core_analog_multiplier_value, uiState.coreConfig.analogMultiplier), { viewModel.updateCoreSettings { it.copy(analogMultiplier = defaults.analogMultiplier) } }) {
            Slider(value = uiState.coreConfig.analogMultiplier, onValueChange = { value -> viewModel.updateCoreSettings { it.copy(analogMultiplier = (value * 10).roundToInt() / 10f) } }, valueRange = 0.5f..2f, steps = 14)
        }
    }
    SectionCard(title = stringResource(R.string.settings_touch_controls_section), contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsSectionContentPadding)) {
        ActionRow(
            title = stringResource(R.string.settings_edit_touch_controls),
            description = stringResource(R.string.settings_help_edit_touch_controls),
            onClick = onOpenTouchControlsEditor
        )
        Toggle(stringResource(R.string.settings_core_gamepad_overlay), stringResource(R.string.settings_help_gamepad_overlay), uiState.coreConfig.enableGamepadOverlay, { enabled -> viewModel.updateCoreSettings { it.copy(enableGamepadOverlay = enabled) } }, { viewModel.updateCoreSettings { it.copy(enableGamepadOverlay = defaults.enableGamepadOverlay) } })
        SliderRow(stringResource(R.string.settings_core_overlay_scale_label), stringResource(R.string.settings_help_overlay_scale), stringResource(R.string.settings_core_overlay_scale_value, uiState.coreConfig.overlayScale), { viewModel.updateCoreSettings { it.copy(overlayScale = defaults.overlayScale) } }) {
            Slider(value = uiState.coreConfig.overlayScale, onValueChange = { value -> viewModel.updateCoreSettings { it.copy(overlayScale = (value * 10).roundToInt() / 10f) } }, valueRange = 0.5f..2f, steps = 14)
        }
        SliderRow(stringResource(R.string.settings_core_overlay_opacity_label), stringResource(R.string.settings_help_overlay_opacity), stringResource(R.string.settings_core_overlay_opacity_value, uiState.coreConfig.overlayOpacity), { viewModel.updateCoreSettings { it.copy(overlayOpacity = defaults.overlayOpacity) } }) {
            Slider(value = uiState.coreConfig.overlayOpacity.toFloat(), onValueChange = { value -> viewModel.updateCoreSettings { it.copy(overlayOpacity = value.roundToInt()) } }, valueRange = 10f..100f, steps = 8)
        }
        Toggle(
            stringResource(R.string.settings_touch_haptics),
            stringResource(R.string.settings_help_touch_haptics),
            uiState.coreConfig.touchHaptics,
            { enabled -> viewModel.updateCoreSettings { it.copy(touchHaptics = enabled) } },
            { viewModel.updateCoreSettings { it.copy(touchHaptics = defaults.touchHaptics) } }
        )
        Chips(
            stringResource(R.string.settings_touch_haptics_preset),
            stringResource(R.string.settings_help_touch_haptics_preset),
            { viewModel.updateCoreSettings { it.copy(touchHapticsPreset = defaults.touchHapticsPreset) } }
        ) {
            IntChip(Ps3CoreConfig.TOUCH_HAPTICS_PRESET_SOFT, stringResource(R.string.settings_touch_haptics_preset_soft), uiState.coreConfig.touchHapticsPreset, viewModel, enabled = uiState.coreConfig.touchHaptics) { config, value -> config.copy(touchHapticsPreset = value) }
            IntChip(Ps3CoreConfig.TOUCH_HAPTICS_PRESET_BALANCED, stringResource(R.string.settings_touch_haptics_preset_balanced), uiState.coreConfig.touchHapticsPreset, viewModel, enabled = uiState.coreConfig.touchHaptics) { config, value -> config.copy(touchHapticsPreset = value) }
            IntChip(Ps3CoreConfig.TOUCH_HAPTICS_PRESET_CRISP, stringResource(R.string.settings_touch_haptics_preset_crisp), uiState.coreConfig.touchHapticsPreset, viewModel, enabled = uiState.coreConfig.touchHaptics) { config, value -> config.copy(touchHapticsPreset = value) }
            IntChip(Ps3CoreConfig.TOUCH_HAPTICS_PRESET_STRONG, stringResource(R.string.settings_touch_haptics_preset_strong), uiState.coreConfig.touchHapticsPreset, viewModel, enabled = uiState.coreConfig.touchHaptics) { config, value -> config.copy(touchHapticsPreset = value) }
        }
        SliderRow(
            stringResource(R.string.settings_touch_haptics_strength),
            stringResource(R.string.settings_help_touch_haptics_strength),
            stringResource(R.string.settings_gamepad_percent_value, uiState.coreConfig.touchHapticsStrength),
            { viewModel.updateCoreSettings { it.copy(touchHapticsStrength = defaults.touchHapticsStrength) } }
        ) {
            Slider(
                enabled = uiState.coreConfig.touchHaptics,
                value = uiState.coreConfig.touchHapticsStrength.toFloat(),
                onValueChange = { value -> viewModel.updateCoreSettings { it.copy(touchHapticsStrength = value.roundToInt().coerceIn(10, 100)) } },
                valueRange = 10f..100f,
                steps = 17
            )
        }
        VibrationTestRow(
            title = stringResource(R.string.settings_touch_haptics_test),
            description = stringResource(R.string.settings_help_touch_haptics_test),
            enabled = uiState.coreConfig.touchHaptics,
            onClick = viewModel::testTouchHaptics
        )
    }
    SectionCard(title = stringResource(R.string.settings_gyro_mode), contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsSectionContentPadding)) {
        Chips(
            stringResource(R.string.settings_gyro_mode),
            stringResource(R.string.settings_help_gyro_mode),
            { viewModel.updateCoreSettings { it.copy(gyroMode = defaults.gyroMode) } }
        ) {
            IntChip(Ps3CoreConfig.GYRO_MODE_OFF, stringResource(R.string.settings_gyro_off), uiState.coreConfig.gyroMode, viewModel) { config, value -> config.copy(gyroMode = value) }
            IntChip(Ps3CoreConfig.GYRO_MODE_AIM, stringResource(R.string.settings_gyro_aim), uiState.coreConfig.gyroMode, viewModel) { config, value -> config.copy(gyroMode = value) }
            IntChip(Ps3CoreConfig.GYRO_MODE_STEERING, stringResource(R.string.settings_gyro_steering), uiState.coreConfig.gyroMode, viewModel) { config, value -> config.copy(gyroMode = value) }
        }
        if (uiState.coreConfig.gyroMode != Ps3CoreConfig.GYRO_MODE_OFF &&
            !AndroidGyroscopeInput.isModeAvailable(context, uiState.coreConfig.gyroMode)
        ) {
            Text(
                text = stringResource(R.string.settings_gyro_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = SettingsSectionRowPadding)
            )
        }
        if (uiState.coreConfig.gyroMode != Ps3CoreConfig.GYRO_MODE_OFF) {
            SliderRow(
                stringResource(R.string.settings_gyro_sensitivity),
                stringResource(R.string.settings_help_gyro_sensitivity),
                stringResource(R.string.settings_gamepad_percent_value, uiState.coreConfig.gyroSensitivity),
                { viewModel.updateCoreSettings { it.copy(gyroSensitivity = defaults.gyroSensitivity) } }
            ) {
                Slider(value = uiState.coreConfig.gyroSensitivity.toFloat(), onValueChange = { value -> viewModel.updateCoreSettings { it.copy(gyroSensitivity = value.roundToInt()) } }, valueRange = 25f..300f, steps = 10)
            }
            SliderRow(
                stringResource(R.string.settings_gyro_smoothing),
                stringResource(R.string.settings_help_gyro_smoothing),
                stringResource(R.string.settings_gamepad_percent_value, uiState.coreConfig.gyroSmoothing),
                { viewModel.updateCoreSettings { it.copy(gyroSmoothing = defaults.gyroSmoothing) } }
            ) {
                Slider(value = uiState.coreConfig.gyroSmoothing.toFloat(), onValueChange = { value -> viewModel.updateCoreSettings { it.copy(gyroSmoothing = value.roundToInt()) } }, valueRange = 0f..90f, steps = 8)
            }
            Toggle(stringResource(R.string.settings_gyro_invert_x), stringResource(R.string.settings_gyro_invert_x_desc), uiState.coreConfig.gyroInvertX, { enabled -> viewModel.updateCoreSettings { it.copy(gyroInvertX = enabled) } }, { viewModel.updateCoreSettings { it.copy(gyroInvertX = defaults.gyroInvertX) } })
            if (uiState.coreConfig.gyroMode == Ps3CoreConfig.GYRO_MODE_AIM) {
                Toggle(stringResource(R.string.settings_gyro_invert_y), stringResource(R.string.settings_gyro_invert_y_desc), uiState.coreConfig.gyroInvertY, { enabled -> viewModel.updateCoreSettings { it.copy(gyroInvertY = enabled) } }, { viewModel.updateCoreSettings { it.copy(gyroInvertY = defaults.gyroInvertY) } })
            }
        }
    }
    SectionCard(title = stringResource(R.string.settings_vibration_section), contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsSectionContentPadding)) {
        Toggle(
            stringResource(R.string.settings_vibration_enable),
            stringResource(R.string.settings_help_vibration_enable),
            uiState.coreConfig.gamepadVibration,
            { enabled -> viewModel.updateCoreSettings { it.copy(gamepadVibration = enabled) } },
            { viewModel.updateCoreSettings { it.copy(gamepadVibration = defaults.gamepadVibration) } }
        )
        SliderRow(
            stringResource(R.string.settings_vibration_strength),
            stringResource(R.string.settings_help_vibration_strength),
            stringResource(R.string.settings_gamepad_percent_value, uiState.coreConfig.gamepadVibrationStrength),
            { viewModel.updateCoreSettings { it.copy(gamepadVibrationStrength = defaults.gamepadVibrationStrength) } }
        ) {
            Slider(
                enabled = uiState.coreConfig.gamepadVibration,
                value = uiState.coreConfig.gamepadVibrationStrength.toFloat(),
                onValueChange = { value -> viewModel.updateCoreSettings { it.copy(gamepadVibrationStrength = value.roundToInt().coerceIn(0, 100)) } },
                valueRange = 0f..100f,
                steps = 19
            )
        }
        VibrationTestRow(
            title = stringResource(R.string.settings_test_vibration),
            description = stringResource(R.string.settings_help_test_vibration),
            enabled = uiState.coreConfig.gamepadVibration && uiState.coreConfig.gamepadVibrationStrength > 0,
            onClick = {
                viewModel.testVibration()
            }
        )
        Toggle(
            stringResource(R.string.settings_device_vibration_fallback),
            stringResource(R.string.settings_help_device_vibration_fallback),
            uiState.coreConfig.deviceVibrationFallback,
            { enabled -> viewModel.updateCoreSettings { it.copy(deviceVibrationFallback = enabled) } },
            { viewModel.updateCoreSettings { it.copy(deviceVibrationFallback = defaults.deviceVibrationFallback) } }
        )
    }
    SectionCard(title = stringResource(R.string.settings_gamepad_section), contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsSectionContentPadding)) {
        Text(
            text = stringResource(
                if (gamepadConnected) R.string.settings_gamepad_connected
                else R.string.settings_gamepad_disconnected
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SettingsSectionRowPadding)
        )
        SliderRow(stringResource(R.string.settings_gamepad_deadzone), stringResource(R.string.settings_help_gamepad_deadzone), stringResource(R.string.settings_gamepad_percent_value, (uiState.coreConfig.gamepadDeadzone * 100f).toInt()), { viewModel.updateCoreSettings { it.copy(gamepadDeadzone = defaults.gamepadDeadzone) } }) {
            Slider(enabled = gamepadConnected, value = uiState.coreConfig.gamepadDeadzone, onValueChange = { value -> viewModel.updateCoreSettings { it.copy(gamepadDeadzone = (value * 100).roundToInt() / 100f) } }, valueRange = 0f..0.45f, steps = 8)
        }
        SliderRow(stringResource(R.string.settings_gamepad_trigger_threshold), stringResource(R.string.settings_help_gamepad_trigger_threshold), stringResource(R.string.settings_gamepad_percent_value, (uiState.coreConfig.gamepadTriggerThreshold * 100f).toInt()), { viewModel.updateCoreSettings { it.copy(gamepadTriggerThreshold = defaults.gamepadTriggerThreshold) } }) {
            Slider(enabled = gamepadConnected, value = uiState.coreConfig.gamepadTriggerThreshold, onValueChange = { value -> viewModel.updateCoreSettings { it.copy(gamepadTriggerThreshold = (value * 100).roundToInt() / 100f) } }, valueRange = 0f..0.9f, steps = 8)
        }
        Chips(stringResource(R.string.settings_gamepad_button_profile), stringResource(R.string.settings_help_gamepad_button_profile), { viewModel.updateCoreSettings { it.copy(gamepadButtonProfile = defaults.gamepadButtonProfile) } }) {
            TextChip(Ps3CoreConfig.GAMEPAD_PROFILE_STANDARD, stringResource(R.string.settings_gamepad_profile_standard), uiState.coreConfig.gamepadButtonProfile, viewModel, enabled = gamepadConnected) { config, value -> config.copy(gamepadButtonProfile = value) }
            TextChip(Ps3CoreConfig.GAMEPAD_PROFILE_SWAP_CROSS_CIRCLE, stringResource(R.string.settings_gamepad_profile_swap_cross_circle), uiState.coreConfig.gamepadButtonProfile, viewModel, enabled = gamepadConnected) { config, value -> config.copy(gamepadButtonProfile = value) }
            TextChip(Ps3CoreConfig.GAMEPAD_PROFILE_NINTENDO_FACE, stringResource(R.string.settings_gamepad_profile_nintendo_face), uiState.coreConfig.gamepadButtonProfile, viewModel, enabled = gamepadConnected) { config, value -> config.copy(gamepadButtonProfile = value) }
        }
        Toggle(stringResource(R.string.settings_gamepad_swap_sticks), stringResource(R.string.settings_help_gamepad_swap_sticks), uiState.coreConfig.gamepadSwapSticks, { enabled -> if (gamepadConnected) viewModel.updateCoreSettings { it.copy(gamepadSwapSticks = enabled) } }, { viewModel.updateCoreSettings { it.copy(gamepadSwapSticks = defaults.gamepadSwapSticks) } })
        Toggle(stringResource(R.string.settings_gamepad_invert_left_y), stringResource(R.string.settings_help_gamepad_invert_y), uiState.coreConfig.gamepadInvertLeftY, { enabled -> if (gamepadConnected) viewModel.updateCoreSettings { it.copy(gamepadInvertLeftY = enabled) } }, { viewModel.updateCoreSettings { it.copy(gamepadInvertLeftY = defaults.gamepadInvertLeftY) } })
        Toggle(stringResource(R.string.settings_gamepad_invert_right_y), stringResource(R.string.settings_help_gamepad_invert_y), uiState.coreConfig.gamepadInvertRightY, { enabled -> if (gamepadConnected) viewModel.updateCoreSettings { it.copy(gamepadInvertRightY = enabled) } }, { viewModel.updateCoreSettings { it.copy(gamepadInvertRightY = defaults.gamepadInvertRightY) } })
    }
}

@Composable
private fun StorageTab(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel,
    selectStorageLocation: (String) -> Unit,
    dismissStorageMigrationDialog: () -> Unit,
    onAddGameFolder: () -> Unit,
    createBackupClick: () -> Unit,
    restoreBackupClick: () -> Unit
) {
    val context = LocalContext.current
    val preferences = remember(context) { com.sbro.emucorec.data.AppPreferences(context) }
    var gameFolders by remember { mutableStateOf(preferences.gameDirectories.toList()) }

    var storagePickerVisible by rememberSaveable { mutableStateOf(false) }
    var pendingStorageRootPath by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingStorageLocation = uiState.storageLocations.firstOrNull { it.rootPath == pendingStorageRootPath }

    SectionCard(
        title = stringResource(R.string.settings_library_folders_title),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsSectionContentPadding)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.settings_library_folders_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (gameFolders.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_library_no_folders),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            } else {
                gameFolders.forEach { folderUri ->
                    val displayName = com.sbro.emucorec.core.DocumentPathResolver.getDisplayName(context, folderUri)
                    Surface(
                        shape = neonShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                val resolvedPath = com.sbro.emucorec.core.DocumentPathResolver.resolveDirectoryPath(context, folderUri)
                                    ?: com.sbro.emucorec.core.DocumentPathResolver.resolveFilePath(context, folderUri)
                                if (resolvedPath != null && resolvedPath != displayName) {
                                    Text(
                                        text = resolvedPath,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                            androidx.compose.material3.IconButton(
                                onClick = {
                                    preferences.removeGameDirectory(folderUri)
                                    gameFolders = preferences.gameDirectories.toList()
                                    com.sbro.emucorec.core.InstallStateBus.notifyCompleted()
                                    Toast.makeText(context, R.string.settings_library_folder_removed, Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Rounded.Delete,
                                    contentDescription = stringResource(R.string.settings_library_remove_folder),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            Button(shape = neonButtonShape(), 
                onClick = onAddGameFolder,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(androidx.compose.material.icons.Icons.Rounded.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings_library_add_folder))
            }
        }
    }

    SectionCard(title = stringResource(R.string.settings_storage_title), contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsSectionContentPadding)) {
        Text(text = stringResource(R.string.settings_storage_body), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f))
        if (uiState.storageLocations.size > 1) {
            Text(
                text = stringResource(R.string.settings_storage_change_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        Text(text = uiState.storagePath, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.primary)
        if (uiState.storageChangeInProgress) {
            Text(
                text = stringResource(R.string.settings_storage_migrating),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Button(shape = neonButtonShape(), 
            onClick = { storagePickerVisible = true },
            enabled = !uiState.storageChangeInProgress,
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text(stringResource(R.string.settings_change_storage))
        }
    }
    SettingsStorageDialog(
        visible = storagePickerVisible,
        storageLocations = uiState.storageLocations,
        enabled = !uiState.storageChangeInProgress,
        onSelected = { location ->
            if (!location.selected) {
                pendingStorageRootPath = location.rootPath
            }
            storagePickerVisible = false
        },
        onDismiss = { storagePickerVisible = false }
    )
    pendingStorageLocation?.let { location ->
        StorageChangeConfirmDialog(
            location = location,
            index = uiState.storageLocations.indexOf(location),
            onConfirm = {
                pendingStorageRootPath = null
                selectStorageLocation(location.rootPath)
            },
            onDismiss = { pendingStorageRootPath = null }
        )
    }
    StorageMigrationDialog(
        state = uiState.storageMigration,
        inProgress = uiState.storageChangeInProgress,
        onDismiss = dismissStorageMigrationDialog
    )
    SectionCard(title = stringResource(R.string.settings_backup_title), contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsSectionContentPadding)) {
        Text(
            text = stringResource(R.string.settings_backup_body),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
        )
        Button(shape = neonButtonShape(), onClick = restoreBackupClick, modifier = Modifier.padding(top = 12.dp)) {
            Text(stringResource(R.string.settings_backup_restore))
        }
        Button(shape = neonButtonShape(), onClick = createBackupClick, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.settings_backup_create))
        }
    }
    ClearCacheSection(cacheSizeBytes = uiState.cacheSizeBytes, viewModel = viewModel)
    ClearCoverCacheSection(coverCacheSizeBytes = uiState.coverCacheSizeBytes, viewModel = viewModel)
}

@Composable
private fun ClearCacheSection(
    cacheSizeBytes: Long,
    viewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var confirmVisible by rememberSaveable { mutableStateOf(false) }
    var clearing by rememberSaveable { mutableStateOf(false) }
    val formattedSize = remember(cacheSizeBytes) {
        android.text.format.Formatter.formatShortFileSize(context, cacheSizeBytes)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshCacheSize()
    }

    SectionCard(
        title = stringResource(R.string.settings_clear_cache_title),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsSectionContentPadding)
    ) {
        Text(
            text = stringResource(R.string.settings_clear_cache_body),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
        )
        Text(
            text = stringResource(R.string.settings_clear_cache_size, formattedSize),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        Button(shape = neonButtonShape(), 
            onClick = { confirmVisible = true },
            enabled = !clearing && cacheSizeBytes > 0L,
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text(stringResource(R.string.settings_clear_cache_action))
        }
        if (cacheSizeBytes <= 0L) {
            Text(
                text = stringResource(R.string.settings_clear_cache_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    if (confirmVisible) {
        AlertDialog(
            onDismissRequest = { if (!clearing) confirmVisible = false },
            title = { Text(stringResource(R.string.settings_clear_cache_confirm_title)) },
            text = { Text(stringResource(R.string.settings_clear_cache_confirm_message, formattedSize)) },
            confirmButton = {
                Button(shape = neonButtonShape(), 
                    enabled = !clearing,
                    onClick = {
                        clearing = true
                        viewModel.clearCaches { result ->
                            clearing = false
                            confirmVisible = false
                            val message = result.fold(
                                onSuccess = { cleared ->
                                    resources.getString(
                                        R.string.settings_clear_cache_done,
                                        android.text.format.Formatter.formatShortFileSize(context, cleared.bytesFreed)
                                    )
                                },
                                onFailure = { resources.getString(R.string.settings_clear_cache_failed) }
                            )
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.settings_clear_cache_confirm_action))
                }
            },
            dismissButton = {
                TextButton(enabled = !clearing, onClick = { confirmVisible = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun StorageChangeConfirmDialog(
    location: Ps3StorageLocation,
    index: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_storage_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.settings_storage_confirm_body, storageLocationLabel(location, index)))
                Text(
                    text = location.ps3Path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(shape = neonButtonShape(), onClick = onConfirm) {
                Text(stringResource(R.string.settings_storage_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun StorageMigrationDialog(
    state: StorageMigrationUiState,
    inProgress: Boolean,
    onDismiss: () -> Unit
) {
    if (!state.visible) return

    val animatedProgress by animateFloatAsState(
        targetValue = state.progress.coerceIn(0f, 1f),
        animationSpec = tween(260),
        label = "storage-migration-progress"
    )
    val hasError = state.errorMessage != null
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp.dp - 36.dp).coerceAtLeast(220.dp)

    Dialog(
        onDismissRequest = {
            if (!inProgress) onDismiss()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !inProgress,
            dismissOnClickOutside = !inProgress
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp)
                .heightIn(max = maxDialogHeight),
            shape = neonShape(30.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f))
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (hasError) {
                        stringResource(R.string.settings_storage_migration_failed)
                    } else {
                        stringResource(R.string.settings_storage_migration_title)
                    },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (hasError) {
                        state.errorMessage.orEmpty()
                    } else {
                        stringResource(R.string.settings_storage_migration_body)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)
                )
                Text(
                    text = stringResource(
                        R.string.settings_storage_migration_count,
                        state.copiedFiles + state.skippedFiles,
                        state.totalFiles
                    ),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.currentPath?.takeIf { it.isNotBlank() }?.let { currentPath ->
                    Text(
                        text = currentPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!inProgress) {
                    Button(shape = neonButtonShape(), 
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.common_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsStorageDialog(
    visible: Boolean,
    storageLocations: List<Ps3StorageLocation>,
    enabled: Boolean,
    onSelected: (Ps3StorageLocation) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            shape = neonShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_change_storage),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (storageLocations.size > 1) {
                        stringResource(R.string.settings_storage_change_note)
                    } else {
                        stringResource(R.string.onboarding_storage_only_default)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                storageLocations.forEachIndexed { index, location ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = neonShape(18.dp),
                        color = if (location.selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        border = BorderStroke(
                            1.dp,
                            if (location.selected) {
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.42f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f)
                            }
                        ),
                        onClick = { if (enabled) onSelected(location) }
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = storageLocationLabel(location, index),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = if (location.selected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                text = location.ps3Path,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (location.selected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.common_close))
                }
            }
        }
    }
}

@Composable
private fun storageLocationLabel(location: Ps3StorageLocation, index: Int): String = when {
    location.removable -> stringResource(R.string.settings_storage_location_sd)
    index == 0 -> stringResource(R.string.settings_storage_location_internal)
    else -> stringResource(R.string.settings_storage_location_external)
}

@Composable
private fun AboutTab() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val packageInfo = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) {
            null
        }
    }
    val versionName = packageInfo?.versionName ?: "1.0.0"

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionCard(
            title = stringResource(R.string.settings_about_title),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsSectionContentPadding)
        ) {
            LinkItem(
                icon = Icons.Rounded.Info,
                title = stringResource(R.string.settings_about_version),
                subtitle = stringResource(R.string.settings_about_version_value, versionName),
                enabled = false,
            )
            LinkItem(
                icon = Icons.Rounded.Memory,
                title = stringResource(R.string.settings_emulation_core),
                subtitle = stringResource(R.string.settings_based_on_rpcsx),
                onClick = { uriHandler.openUri(RPCS3RepositoryUrl) }
            )
            Text(
                text = stringResource(R.string.settings_about_body_extended),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = SettingsSectionRowPadding)
            )
        }

        SectionCard(
            title = stringResource(R.string.settings_about_community_title),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsSectionContentPadding)
        ) {
            LinkItem(
                icon = Icons.Rounded.Language,
                title = stringResource(R.string.settings_about_website),
                subtitle = stringResource(R.string.settings_about_website_desc),
                onClick = { uriHandler.openUri(EmuCoreWebsiteUrl) }
            )
            LinkItem(
                icon = Icons.Rounded.Code,
                title = stringResource(R.string.settings_about_repository),
                subtitle = stringResource(R.string.settings_about_repository_desc),
                onClick = { uriHandler.openUri(EmuCoreRepositoryUrl) }
            )
            LinkItem(
                icon = Icons.Rounded.Favorite,
                title = stringResource(R.string.settings_about_support),
                subtitle = stringResource(R.string.settings_about_support_desc),
                onClick = { uriHandler.openUri(EmuCoreSupportUrl) }
            )
            LinkItem(
                icon = Icons.Rounded.Info,
                title = stringResource(R.string.settings_about_privacy_policy),
                subtitle = stringResource(R.string.settings_about_privacy_policy_desc),
                onClick = { uriHandler.openUri(PrivacyPolicyUrl) }
            )
        }

        SectionCard(
            title = stringResource(R.string.settings_developer),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsSectionContentPadding)
        ) {
            Text(
                text = stringResource(R.string.settings_created_by_sbro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = SettingsSectionRowPadding)
            )
            LinkItem(
                icon = Icons.Rounded.Link,
                title = stringResource(R.string.settings_play_store_profile),
                subtitle = stringResource(R.string.settings_play_store_profile_desc),
                onClick = { uriHandler.openUri(SashkinAppsPlayStoreUrl) }
            )
        }

        SectionCard(
            title = stringResource(R.string.settings_core_source_code),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsSectionContentPadding)
        ) {
            Text(
                text = stringResource(R.string.settings_core_source_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = SettingsSectionRowPadding)
            )
            LinkItem(
                icon = Icons.Rounded.Link,
                title = "RPCS3",
                subtitle = stringResource(R.string.settings_rpcs3_repository_desc),
                onClick = { uriHandler.openUri(RPCS3RepositoryUrl) }
            )
        }
    }
}

@Composable
private fun LinkItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit = {},
    enabled: Boolean = true,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SettingsCardInnerPadding, vertical = SettingsCardInnerPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(neonShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Toggle(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, onResetDefault: () -> Unit, enabled: Boolean = true) =
    SettingToggleRow(title = title, description = description, checked = checked, onCheckedChange = onCheckedChange, onResetDefault = onResetDefault, enabled = enabled)

@Composable
private fun Chips(title: String, description: String, onResetDefault: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
    SettingChoiceRow(title = title, description = description, onResetDefault = onResetDefault, content = content)

@Composable
private fun SliderRow(title: String, description: String, valueText: String, onResetDefault: () -> Unit, content: @Composable () -> Unit) =
    SettingSliderRow(title = title, description = description, valueText = valueText, onResetDefault = onResetDefault, slider = content)

@Composable
private fun ActionRow(
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SettingsCardInnerPadding, vertical = SettingsCardInnerPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun VibrationTestRow(
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsSectionRowPadding),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SettingsCardInnerPadding, vertical = SettingsCardInnerPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(shape = neonButtonShape(), 
                onClick = onClick,
                enabled = enabled
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings_test_vibration_button))
            }
        }
    }
}

@Composable
private fun AppLanguageSettingRow(
    selectedLanguage: AppLanguage,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsSectionRowPadding),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsCardInnerPadding, vertical = SettingsCardInnerPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(neonShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_app_language),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = appLanguageLabel(selectedLanguage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun KeepScreenOnSettingRow(
    keepScreenOn: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsSectionRowPadding),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)),
        onClick = { onCheckedChange(!keepScreenOn) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsCardInnerPadding, vertical = SettingsCardInnerPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(neonShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.StayCurrentPortrait,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_keep_screen_on),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.settings_keep_screen_on_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = keepScreenOn,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun ClearCoverCacheSection(
    coverCacheSizeBytes: Long,
    viewModel: SettingsViewModel
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var confirmVisible by rememberSaveable { mutableStateOf(false) }
    var clearing by rememberSaveable { mutableStateOf(false) }
    val formattedSize = remember(coverCacheSizeBytes) {
        android.text.format.Formatter.formatShortFileSize(context, coverCacheSizeBytes)
    }

    LaunchedEffect(Unit) {
        viewModel.refreshCoverCacheSize()
    }

    SectionCard(
        title = stringResource(R.string.settings_clear_cover_cache_title),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(SettingsSectionContentPadding)
    ) {
        Text(
            text = stringResource(R.string.settings_clear_cover_cache_body),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
        )
        Text(
            text = stringResource(R.string.settings_clear_cover_cache_size, formattedSize),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        Button(shape = neonButtonShape(), 
            onClick = { confirmVisible = true },
            enabled = !clearing && coverCacheSizeBytes > 0L,
            modifier = Modifier.padding(top = 12.dp)
        ) {
            Text(stringResource(R.string.settings_clear_cover_cache_action))
        }
        if (coverCacheSizeBytes <= 0L) {
            Text(
                text = stringResource(R.string.settings_clear_cover_cache_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    if (confirmVisible) {
        AlertDialog(
            onDismissRequest = { if (!clearing) confirmVisible = false },
            title = { Text(stringResource(R.string.settings_clear_cover_cache_confirm_title)) },
            text = { Text(stringResource(R.string.settings_clear_cover_cache_confirm_message, formattedSize)) },
            confirmButton = {
                Button(shape = neonButtonShape(), 
                    enabled = !clearing,
                    onClick = {
                        clearing = true
                        viewModel.clearCoverCache { result ->
                            clearing = false
                            confirmVisible = false
                            val message = result.fold(
                                onSuccess = { freed ->
                                    resources.getString(
                                        R.string.settings_clear_cover_cache_done,
                                        android.text.format.Formatter.formatShortFileSize(context, freed)
                                    )
                                },
                                onFailure = { resources.getString(R.string.settings_clear_cover_cache_failed) }
                            )
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.settings_clear_cover_cache_confirm_action))
                }
            },
            dismissButton = {
                TextButton(enabled = !clearing, onClick = { confirmVisible = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun appLanguageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> stringResource(R.string.settings_app_language_system)
    AppLanguage.ENGLISH -> stringResource(R.string.settings_app_language_english)
    AppLanguage.RUSSIAN -> stringResource(R.string.settings_app_language_russian)
    AppLanguage.UKRAINIAN -> stringResource(R.string.settings_app_language_ukrainian)
    AppLanguage.SPANISH -> stringResource(R.string.settings_app_language_spanish)
    AppLanguage.FRENCH -> stringResource(R.string.settings_app_language_french)
    AppLanguage.GERMAN -> stringResource(R.string.settings_app_language_german)
    AppLanguage.PORTUGUESE -> stringResource(R.string.settings_app_language_portuguese)
    AppLanguage.CHINESE -> stringResource(R.string.settings_app_language_chinese_traditional)
    AppLanguage.HINDI -> stringResource(R.string.settings_app_language_hindi)
    AppLanguage.ITALIAN -> stringResource(R.string.settings_app_language_italian)
    AppLanguage.TURKISH -> stringResource(R.string.settings_app_language_turkish)
    AppLanguage.ARABIC -> stringResource(R.string.settings_app_language_arabic)
}

@Composable
private fun IntChip(
    value: Int,
    label: String,
    current: Int,
    viewModel: SettingsViewModel,
    enabled: Boolean = true,
    transform: (Ps3CoreConfig, Int) -> Ps3CoreConfig
) {
    FilterChip(shape = neonChipShape(), 
        selected = current == value,
        enabled = enabled,
        onClick = { viewModel.updateCoreSettings { config -> transform(config, value) } },
        colors = appFilterChipColors(),
        label = { Text(label) }
    )
}

@Composable
private fun TextChip(
    value: String,
    current: String,
    viewModel: SettingsViewModel,
    transform: (Ps3CoreConfig, String) -> Ps3CoreConfig
) = TextChip(
    value = value,
    label = value,
    current = current,
    viewModel = viewModel,
    transform = transform
)

@Composable
private fun TextChip(
    value: String,
    label: String,
    current: String,
    viewModel: SettingsViewModel,
    enabled: Boolean = true,
    transform: (Ps3CoreConfig, String) -> Ps3CoreConfig
) {
    FilterChip(shape = neonChipShape(), 
        selected = current == value,
        onClick = { viewModel.updateCoreSettings { config -> transform(config, value) } },
        enabled = enabled,
        colors = appFilterChipColors(),
        label = { Text(label) }
    )
}

@Composable
private fun appFilterChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    labelColor = MaterialTheme.colorScheme.onSurface,
    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer
)
