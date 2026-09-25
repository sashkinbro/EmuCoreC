package com.sbro.emucorec.ui.gamemanager

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.sbro.emucorec.ui.settings.animateScrollToCenterItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sbro.emucorec.R
import com.sbro.emucorec.core.GpuDriverCompatibility
import com.sbro.emucorec.core.InstalledGpuDriver
import com.sbro.emucorec.core.Ps3CoreConfig
import com.sbro.emucorec.data.InstalledPs3Game
import com.sbro.emucorec.ui.common.LocalImage
import com.sbro.emucorec.ui.common.EmuCoreLoadingAnimation
import com.sbro.emucorec.ui.common.ScreenTopBar
import com.sbro.emucorec.ui.common.SectionCard
import com.sbro.emucorec.ui.common.SettingHelpButton
import com.sbro.emucorec.ui.settings.Ps3CoreSettingsCategory
import com.sbro.emucorec.ui.settings.Ps3CoreSettingsSection
import com.sbro.emucorec.ui.settings.Ps3CoreSettingsScope
import com.sbro.emucorec.ui.settings.Ps3CoreSettingsSurface
import com.sbro.emucorec.ui.theme.CardContentPadding
import com.sbro.emucorec.ui.theme.ScreenContentBottomPadding
import com.sbro.emucorec.ui.theme.ScreenHorizontalPadding
import kotlin.math.roundToInt
import com.sbro.emucorec.ui.theme.neon.neonShape
import com.sbro.emucorec.ui.theme.neon.neonChipShape
import com.sbro.emucorec.ui.theme.neon.neonButtonShape

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameManagerScreen(
    initialTitleId: String? = null,
    onMenuClick: (() -> Unit)? = null,
    onBackClick: (() -> Unit)? = null,
    onOpenGpuDriverManager: (String?) -> Unit = {},
    onOpenTouchControlsEditor: (String?) -> Unit = {},
    viewModel: GameManagerViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val resumeTitleId by rememberUpdatedState(initialTitleId ?: uiState.selectedTitleId)
    val topInset = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    var selectedTab by remember { mutableStateOf(GameManagerTab.General) }

    androidx.compose.runtime.LaunchedEffect(initialTitleId) {
        initialTitleId?.takeIf(String::isNotBlank)?.let(viewModel::selectGame)
    }

    DisposableEffect(lifecycleOwner, initialTitleId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh(resumeTitleId)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = topInset,
            bottom = ScreenContentBottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenTopBar(
                title = stringResource(R.string.game_manager_title),
                onBackClick = onBackClick,
                onMenuClick = onMenuClick,
                modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)
            )
        }

        if (uiState.isLoading && !uiState.hasLoadedOnce) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    EmuCoreLoadingAnimation()
                }
            }
        } else if (uiState.games.isEmpty()) {
            item {
                EmptyGameManagerState()
            }
        } else {
            item {
                GamePicker(
                    games = uiState.games,
                    selectedTitleId = uiState.selectedTitleId,
                    selectedGame = uiState.selectedGame,
                    hasCustomProfile = uiState.hasCustomProfile,
                    onReset = viewModel::resetSelectedToGlobal,
                    onSelect = viewModel::selectGame
                )
            }
            item {
                GameManagerTabs(selectedTab = selectedTab, onSelected = { selectedTab = it })
            }
            item {
                when (selectedTab) {
                    GameManagerTab.General -> GameCoreSettings(Ps3CoreSettingsCategory.General, uiState.selectedTitleId, viewModel::refreshCustomProfileFlag)
                    GameManagerTab.Graphics -> {
                        GraphicsProfileSection(
                            config = uiState.config,
                            defaults = uiState.defaults,
                            installedGpuDrivers = uiState.installedGpuDrivers,
                            customDriverOverride = uiState.customDriverOverride,
                            onOpenGpuDriverManager = { onOpenGpuDriverManager(uiState.selectedTitleId) },
                            onDriverOverrideSelected = viewModel::selectCustomDriverOverride
                        )
                        GameCoreSettings(
                            Ps3CoreSettingsCategory.Graphics,
                            uiState.selectedTitleId,
                            viewModel::refreshCustomProfileFlag,
                            additionalCategories = setOf(Ps3CoreSettingsCategory.Overlay),
                        )
                    }
                    GameManagerTab.Audio -> GameCoreSettings(Ps3CoreSettingsCategory.Audio, uiState.selectedTitleId, viewModel::refreshCustomProfileFlag)
                    GameManagerTab.Controls -> {
                        GamepadProfileSection(
                            uiState.config,
                            uiState.defaults,
                            viewModel::updateSelected,
                            onOpenTouchControlsEditor = {
                                onOpenTouchControlsEditor(uiState.selectedTitleId)
                            }
                        )
                        GameCoreSettings(Ps3CoreSettingsCategory.Controls, uiState.selectedTitleId, viewModel::refreshCustomProfileFlag)
                    }
                    GameManagerTab.Network -> GameCoreSettings(Ps3CoreSettingsCategory.Network, uiState.selectedTitleId, viewModel::refreshCustomProfileFlag)
                    GameManagerTab.Advanced -> GameCoreSettings(Ps3CoreSettingsCategory.Advanced, uiState.selectedTitleId, viewModel::refreshCustomProfileFlag)
                }
            }
        }
    }
}

private enum class GameManagerTab {
    General,
    Graphics,
    Audio,
    Controls,
    Network,
    Advanced,
}

@Composable
private fun GameCoreSettings(
    category: Ps3CoreSettingsCategory,
    titleId: String?,
    onOverridesChanged: () -> Unit,
    additionalCategories: Set<Ps3CoreSettingsCategory> = emptySet(),
) {
    Ps3CoreSettingsSection(
        category = category,
        additionalCategories = additionalCategories,
        scope = Ps3CoreSettingsScope.Game,
        surface = Ps3CoreSettingsSurface.GameProfile,
        titleId = titleId,
        onOverridesChanged = onOverridesChanged,
    )
}

@Composable
private fun GameManagerTabs(
    selectedTab: GameManagerTab,
    onSelected: (GameManagerTab) -> Unit
) {
    val tabs = remember { GameManagerTab.entries.toList() }
    val listState = rememberLazyListState()
    LaunchedEffect(selectedTab) {
        val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
        listState.animateScrollToCenterItem(selectedIndex)
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = ScreenHorizontalPadding)
    ) {
        items(tabs, key = { it.name }) { tab ->
            FilterChip(shape = neonChipShape(), 
                selected = selectedTab == tab,
                onClick = { onSelected(tab) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                label = {
                    Text(
                        text = when (tab) {
                            GameManagerTab.General -> stringResource(R.string.settings_tab_general)
                            GameManagerTab.Graphics -> stringResource(R.string.settings_tab_graphics)
                            GameManagerTab.Audio -> stringResource(R.string.settings_tab_audio)
                            GameManagerTab.Controls -> stringResource(R.string.settings_tab_controls)
                            GameManagerTab.Network -> stringResource(R.string.settings_tab_network)
                            GameManagerTab.Advanced -> stringResource(R.string.settings_tab_advanced)
                        },
                        maxLines = 1,
                        softWrap = false
                    )
                }
            )
        }
    }
}

@Composable
private fun EmptyGameManagerState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.SportsEsports,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.game_manager_empty_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.game_manager_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun GamePicker(
    games: List<InstalledPs3Game>,
    selectedTitleId: String?,
    selectedGame: InstalledPs3Game?,
    hasCustomProfile: Boolean,
    onReset: () -> Unit,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.game_manager_choose_game),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = selectedGame?.let { "${it.title} · ${it.titleId}" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onReset,
                enabled = hasCustomProfile
            ) {
                Icon(
                    imageVector = Icons.Rounded.RestartAlt,
                    contentDescription = stringResource(R.string.game_manager_reset_global)
                )
            }
        }
        val listState = rememberLazyListState()
        LaunchedEffect(selectedTitleId, games) {
            val selectedIndex = games.indexOfFirst { it.titleId.equals(selectedTitleId, ignoreCase = true) }
            if (selectedIndex >= 0) {
                listState.animateScrollToCenterItem(selectedIndex)
            }
        }
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = ScreenHorizontalPadding)
        ) {
            items(games, key = { it.titleId }) { game ->
                val selected = game.titleId == selectedTitleId
                Surface(
                    onClick = { onSelect(game.titleId) },
                    shape = neonShape(18.dp),
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.68f) else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = neonShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        ) {
                            LocalImage(
                                path = game.iconPath,
                                contentDescription = game.title,
                                fallbackLabel = game.title,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Column(modifier = Modifier.widthIn(min = 120.dp, max = 160.dp).height(46.dp)) {
                            Text(
                                text = game.title,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = game.titleId,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphicsProfileSection(
    config: Ps3CoreConfig,
    defaults: Ps3CoreConfig,
    installedGpuDrivers: List<InstalledGpuDriver>,
    customDriverOverride: String?,
    onOpenGpuDriverManager: () -> Unit,
    onDriverOverrideSelected: (String?) -> Unit
) {
    SectionCard(title = stringResource(R.string.settings_tab_graphics)) {
        if (remember { GpuDriverCompatibility.supportsAdrenoToolsCustomDrivers() }) {
            GpuDriverChoiceRow(
                effectiveDriverName = config.customDriverName,
                globalDriverName = defaults.customDriverName,
                customDriverOverride = customDriverOverride,
                installedGpuDrivers = installedGpuDrivers,
                onOpenGpuDriverManager = onOpenGpuDriverManager,
                onReset = { onDriverOverrideSelected(null) },
                onSelected = onDriverOverrideSelected
            )
        }
        Text(
            text = stringResource(R.string.settings_gpu_driver_manager_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GpuDriverChoiceRow(
    effectiveDriverName: String,
    globalDriverName: String,
    customDriverOverride: String?,
    installedGpuDrivers: List<InstalledGpuDriver>,
    onOpenGpuDriverManager: () -> Unit,
    onReset: () -> Unit,
    onSelected: (String?) -> Unit
) {
    val selectedDriver = installedGpuDrivers.firstOrNull { it.name == effectiveDriverName }
    val status = when {
        customDriverOverride == null && globalDriverName.isBlank() -> stringResource(R.string.settings_gpu_driver_status_global_system)
        customDriverOverride == null -> stringResource(R.string.settings_gpu_driver_status_global, globalDriverName)
        customDriverOverride.isBlank() -> stringResource(R.string.settings_gpu_driver_status_game_system)
        selectedDriver == null -> stringResource(R.string.settings_gpu_driver_status_broken, effectiveDriverName)
        !selectedDriver.isUsable -> stringResource(R.string.settings_gpu_driver_status_broken, selectedDriver.name)
        else -> stringResource(R.string.settings_gpu_driver_status_game_active, selectedDriver.name)
    }

    SettingContainer(
        title = stringResource(R.string.settings_gpu_driver),
        description = stringResource(R.string.settings_help_gpu_driver),
        onReset = onReset
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val selectedLabel = when {
            customDriverOverride == null -> stringResource(R.string.settings_gpu_driver_global)
            customDriverOverride.isBlank() -> stringResource(R.string.settings_gpu_driver_system)
            else -> customDriverOverride
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(shape = neonChipShape(), 
                selected = true,
                onClick = onOpenGpuDriverManager,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                label = {
                    Text(
                        text = selectedLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
        if (installedGpuDrivers.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_gpu_driver_none_installed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(shape = neonButtonShape(), 
            onClick = onOpenGpuDriverManager,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_gpu_driver_manage))
        }
    }
}

@Composable
private fun GamepadProfileSection(
    config: Ps3CoreConfig,
    defaults: Ps3CoreConfig,
    onUpdate: ((Ps3CoreConfig) -> Ps3CoreConfig) -> Unit,
    onOpenTouchControlsEditor: () -> Unit
) {
    SectionCard(title = stringResource(R.string.settings_touch_controls_section)) {
        TouchControlsEditRow(onOpenTouchControlsEditor)
        ToggleRow(
            stringResource(R.string.settings_core_gamepad_overlay),
            config.enableGamepadOverlay,
            stringResource(R.string.settings_help_gamepad_overlay),
            { onUpdate { it.copy(enableGamepadOverlay = defaults.enableGamepadOverlay) } }
        ) { enabled -> onUpdate { it.copy(enableGamepadOverlay = enabled) } }
        SliderRow(
            title = stringResource(R.string.settings_core_overlay_scale_label),
            description = stringResource(R.string.settings_help_overlay_scale),
            valueText = stringResource(R.string.settings_core_overlay_scale_value, config.overlayScale),
            value = config.overlayScale,
            valueRange = 0.5f..2f,
            steps = 14,
            onReset = { onUpdate { it.copy(overlayScale = defaults.overlayScale) } },
            onChange = { value -> onUpdate { it.copy(overlayScale = (value * 10).roundToInt() / 10f) } }
        )
        SliderRow(
            title = stringResource(R.string.settings_core_overlay_opacity_label),
            description = stringResource(R.string.settings_help_overlay_opacity),
            valueText = stringResource(R.string.settings_core_overlay_opacity_value, config.overlayOpacity),
            value = config.overlayOpacity.toFloat(),
            valueRange = 10f..100f,
            steps = 8,
            onReset = { onUpdate { it.copy(overlayOpacity = defaults.overlayOpacity) } },
            onChange = { value -> onUpdate { it.copy(overlayOpacity = value.roundToInt()) } }
        )
        ToggleRow(
            stringResource(R.string.settings_touch_haptics),
            config.touchHaptics,
            stringResource(R.string.settings_help_touch_haptics),
            { onUpdate { it.copy(touchHaptics = defaults.touchHaptics) } }
        ) { enabled -> onUpdate { it.copy(touchHaptics = enabled) } }
        IntChoiceRow(
            title = stringResource(R.string.settings_touch_haptics_preset),
            description = stringResource(R.string.settings_help_touch_haptics_preset),
            selected = config.touchHapticsPreset,
            options = listOf(
                Ps3CoreConfig.TOUCH_HAPTICS_PRESET_SOFT to stringResource(R.string.settings_touch_haptics_preset_soft),
                Ps3CoreConfig.TOUCH_HAPTICS_PRESET_BALANCED to stringResource(R.string.settings_touch_haptics_preset_balanced),
                Ps3CoreConfig.TOUCH_HAPTICS_PRESET_CRISP to stringResource(R.string.settings_touch_haptics_preset_crisp),
                Ps3CoreConfig.TOUCH_HAPTICS_PRESET_STRONG to stringResource(R.string.settings_touch_haptics_preset_strong)
            ),
            enabled = config.touchHaptics,
            onReset = { onUpdate { it.copy(touchHapticsPreset = defaults.touchHapticsPreset) } }
        ) { value -> onUpdate { it.copy(touchHapticsPreset = value) } }
        SliderRow(
            title = stringResource(R.string.settings_touch_haptics_strength),
            description = stringResource(R.string.settings_help_touch_haptics_strength),
            valueText = stringResource(R.string.settings_gamepad_percent_value, config.touchHapticsStrength),
            value = config.touchHapticsStrength.toFloat(),
            valueRange = 10f..100f,
            steps = 17,
            enabled = config.touchHaptics,
            onReset = { onUpdate { it.copy(touchHapticsStrength = defaults.touchHapticsStrength) } },
            onChange = { value -> onUpdate { it.copy(touchHapticsStrength = value.roundToInt()) } }
        )
    }
    SectionCard(title = stringResource(R.string.settings_gyro_mode)) {
        IntChoiceRow(
            title = stringResource(R.string.settings_gyro_mode),
            description = stringResource(R.string.settings_help_gyro_mode),
            selected = config.gyroMode,
            options = listOf(
                Ps3CoreConfig.GYRO_MODE_OFF to stringResource(R.string.settings_gyro_off),
                Ps3CoreConfig.GYRO_MODE_AIM to stringResource(R.string.settings_gyro_aim),
                Ps3CoreConfig.GYRO_MODE_STEERING to stringResource(R.string.settings_gyro_steering)
            ),
            onReset = { onUpdate { it.copy(gyroMode = defaults.gyroMode) } }
        ) { value -> onUpdate { it.copy(gyroMode = value) } }
        if (config.gyroMode != Ps3CoreConfig.GYRO_MODE_OFF) {
            SliderRow(
                title = stringResource(R.string.settings_gyro_sensitivity),
                description = stringResource(R.string.settings_help_gyro_sensitivity),
                valueText = stringResource(R.string.settings_gamepad_percent_value, config.gyroSensitivity),
                value = config.gyroSensitivity.toFloat(),
                valueRange = 25f..300f,
                steps = 10,
                onReset = { onUpdate { it.copy(gyroSensitivity = defaults.gyroSensitivity) } },
                onChange = { value -> onUpdate { it.copy(gyroSensitivity = value.roundToInt()) } }
            )
            SliderRow(
                title = stringResource(R.string.settings_gyro_smoothing),
                description = stringResource(R.string.settings_help_gyro_smoothing),
                valueText = stringResource(R.string.settings_gamepad_percent_value, config.gyroSmoothing),
                value = config.gyroSmoothing.toFloat(),
                valueRange = 0f..90f,
                steps = 8,
                onReset = { onUpdate { it.copy(gyroSmoothing = defaults.gyroSmoothing) } },
                onChange = { value -> onUpdate { it.copy(gyroSmoothing = value.roundToInt()) } }
            )
            ToggleRow(stringResource(R.string.settings_gyro_invert_x), config.gyroInvertX, stringResource(R.string.settings_gyro_invert_x_desc), { onUpdate { it.copy(gyroInvertX = defaults.gyroInvertX) } }) { enabled -> onUpdate { it.copy(gyroInvertX = enabled) } }
            if (config.gyroMode == Ps3CoreConfig.GYRO_MODE_AIM) {
                ToggleRow(stringResource(R.string.settings_gyro_invert_y), config.gyroInvertY, stringResource(R.string.settings_gyro_invert_y_desc), { onUpdate { it.copy(gyroInvertY = defaults.gyroInvertY) } }) { enabled -> onUpdate { it.copy(gyroInvertY = enabled) } }
            }
        }
    }
    SectionCard(title = stringResource(R.string.settings_gamepad_section)) {
        SliderRow(
            title = stringResource(R.string.settings_gamepad_deadzone),
            description = stringResource(R.string.settings_help_gamepad_deadzone),
            valueText = stringResource(R.string.settings_gamepad_percent_value, (config.gamepadDeadzone * 100).toInt()),
            value = config.gamepadDeadzone,
            valueRange = 0f..0.45f,
            steps = 8,
            onReset = { onUpdate { it.copy(gamepadDeadzone = defaults.gamepadDeadzone) } },
            onChange = { value -> onUpdate { it.copy(gamepadDeadzone = value) } }
        )
        SliderRow(
            title = stringResource(R.string.settings_core_analog_multiplier_label),
            description = stringResource(R.string.settings_help_analog_multiplier),
            valueText = stringResource(R.string.settings_core_analog_multiplier_value, config.analogMultiplier),
            value = config.analogMultiplier,
            valueRange = 0.5f..2f,
            steps = 14,
            onReset = { onUpdate { it.copy(analogMultiplier = defaults.analogMultiplier) } },
            onChange = { value -> onUpdate { it.copy(analogMultiplier = (value * 10).toInt() / 10f) } }
        )
        SliderRow(
            title = stringResource(R.string.settings_gamepad_trigger_threshold),
            description = stringResource(R.string.settings_help_gamepad_trigger_threshold),
            valueText = stringResource(R.string.settings_gamepad_percent_value, (config.gamepadTriggerThreshold * 100).toInt()),
            value = config.gamepadTriggerThreshold,
            valueRange = 0f..0.9f,
            steps = 8,
            onReset = { onUpdate { it.copy(gamepadTriggerThreshold = defaults.gamepadTriggerThreshold) } },
            onChange = { value -> onUpdate { it.copy(gamepadTriggerThreshold = value) } }
        )
        ChoiceRow(
            title = stringResource(R.string.settings_gamepad_button_profile),
            description = stringResource(R.string.settings_help_gamepad_button_profile),
            selected = config.gamepadButtonProfile,
            options = listOf(
                Ps3CoreConfig.GAMEPAD_PROFILE_STANDARD to stringResource(R.string.settings_gamepad_profile_standard),
                Ps3CoreConfig.GAMEPAD_PROFILE_SWAP_CROSS_CIRCLE to stringResource(R.string.settings_gamepad_profile_swap_cross_circle),
                Ps3CoreConfig.GAMEPAD_PROFILE_NINTENDO_FACE to stringResource(R.string.settings_gamepad_profile_nintendo_face)
            ),
            onReset = { onUpdate { it.copy(gamepadButtonProfile = defaults.gamepadButtonProfile) } }
        ) {
            onUpdate { cfg -> cfg.copy(gamepadButtonProfile = it) }
        }
        ToggleRow(stringResource(R.string.settings_vibration_enable), config.gamepadVibration, stringResource(R.string.settings_help_vibration_enable), { onUpdate { it.copy(gamepadVibration = defaults.gamepadVibration) } }) { onUpdate { cfg -> cfg.copy(gamepadVibration = it) } }
        SliderRow(
            title = stringResource(R.string.settings_vibration_strength),
            description = stringResource(R.string.settings_help_vibration_strength),
            valueText = stringResource(R.string.settings_gamepad_percent_value, config.gamepadVibrationStrength),
            value = config.gamepadVibrationStrength.toFloat(),
            valueRange = 0f..100f,
            steps = 19,
            onReset = { onUpdate { it.copy(gamepadVibrationStrength = defaults.gamepadVibrationStrength) } },
            onChange = { value -> onUpdate { it.copy(gamepadVibrationStrength = value.toInt().coerceIn(0, 100)) } }
        )
        ToggleRow(stringResource(R.string.settings_device_vibration_fallback), config.deviceVibrationFallback, stringResource(R.string.settings_help_device_vibration_fallback), { onUpdate { it.copy(deviceVibrationFallback = defaults.deviceVibrationFallback) } }) { onUpdate { cfg -> cfg.copy(deviceVibrationFallback = it) } }
        ToggleRow(stringResource(R.string.settings_gamepad_swap_sticks), config.gamepadSwapSticks, stringResource(R.string.settings_help_gamepad_swap_sticks), { onUpdate { it.copy(gamepadSwapSticks = defaults.gamepadSwapSticks) } }) { onUpdate { cfg -> cfg.copy(gamepadSwapSticks = it) } }
        ToggleRow(stringResource(R.string.game_manager_invert_left_x), config.gamepadInvertLeftX, stringResource(R.string.settings_help_gamepad_invert_x), { onUpdate { it.copy(gamepadInvertLeftX = defaults.gamepadInvertLeftX) } }) { onUpdate { cfg -> cfg.copy(gamepadInvertLeftX = it) } }
        ToggleRow(stringResource(R.string.settings_gamepad_invert_left_y), config.gamepadInvertLeftY, stringResource(R.string.settings_help_gamepad_invert_y), { onUpdate { it.copy(gamepadInvertLeftY = defaults.gamepadInvertLeftY) } }) { onUpdate { cfg -> cfg.copy(gamepadInvertLeftY = it) } }
        ToggleRow(stringResource(R.string.game_manager_invert_right_x), config.gamepadInvertRightX, stringResource(R.string.settings_help_gamepad_invert_x), { onUpdate { it.copy(gamepadInvertRightX = defaults.gamepadInvertRightX) } }) { onUpdate { cfg -> cfg.copy(gamepadInvertRightX = it) } }
        ToggleRow(stringResource(R.string.settings_gamepad_invert_right_y), config.gamepadInvertRightY, stringResource(R.string.settings_help_gamepad_invert_y), { onUpdate { it.copy(gamepadInvertRightY = defaults.gamepadInvertRightY) } }) { onUpdate { cfg -> cfg.copy(gamepadInvertRightY = it) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(
    title: String,
    description: String = title,
    selected: String,
    options: List<Pair<String, String>>,
    onReset: (() -> Unit)? = null,
    onSelected: (String) -> Unit
) {
    SettingContainer(title = title, description = description, onReset = onReset) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                FilterChip(shape = neonChipShape(), 
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    label = { Text(label) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntChoiceRow(
    title: String,
    description: String = title,
    selected: Int,
    options: List<Pair<Int, String>>,
    enabled: Boolean = true,
    onReset: (() -> Unit)? = null,
    onSelected: (Int) -> Unit
) {
    SettingContainer(
        title = title,
        description = description,
        onReset = onReset,
        enabled = enabled
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (value, label) ->
                FilterChip(shape = neonChipShape(), 
                    selected = selected == value,
                    enabled = enabled,
                    onClick = { onSelected(value) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    label = { Text(label) }
                )
            }
        }
    }
}

@Composable
private fun TouchControlsEditRow(onClick: () -> Unit) {
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
            modifier = Modifier.padding(CardContentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.settings_edit_touch_controls),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.settings_help_edit_touch_controls),
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
private fun ToggleRow(
    title: String,
    checked: Boolean,
    description: String = title,
    onReset: (() -> Unit)? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingContainer(title = title, description = description, onReset = onReset, enabled = enabled) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    description: String = title,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    onReset: () -> Unit,
    onChange: (Float) -> Unit
) {
    SettingContainer(title = title, description = description, onReset = onReset, enabled = enabled) {
        Text(
            text = valueText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            enabled = enabled,
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            steps = steps
        )
    }
}

@Composable
private fun SettingContainer(
    title: String,
    description: String,
    onReset: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { onReset?.invoke() }
            ),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier.padding(CardContentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                SettingHelpButton(title = title, description = description)
            }
            content()
        }
    }
}
