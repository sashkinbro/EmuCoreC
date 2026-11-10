@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.sbro.emucorec.ui.emulation

import com.sbro.emucorec.ui.common.appSliderColors

import com.sbro.emucorec.ui.common.appSwitchColors

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.sbro.emucorec.core.SaveStateRepository
import com.sbro.emucorec.core.SaveStateSlot
import com.sbro.emucorec.ui.settings.centeredTabScrollDelta
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import com.sbro.emucorec.ui.theme.GradientStart
import com.sbro.emucorec.ui.theme.GradientEnd
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sbro.emucorec.R
import com.sbro.emucorec.core.AndroidGyroscopeInput
import com.sbro.emucorec.core.Ps3CoreConfig
import com.sbro.emucorec.data.TrophyRepository
import com.sbro.emucorec.data.GameMenuLayoutStyle
import com.sbro.emucorec.data.Ps3Trophy
import com.sbro.emucorec.data.Ps3TrophyGrade
import com.sbro.emucorec.data.Ps3TrophySet
import com.sbro.emucorec.ui.common.COVER_ART_ASPECT_RATIO
import com.sbro.emucorec.ui.common.LocalImage
import com.sbro.emucorec.ui.common.SettingHelpButton
import com.sbro.emucorec.ui.common.UrlImage
import com.sbro.emucorec.ui.common.appFilterChipColors
import com.sbro.emucorec.ui.settings.Ps3CoreSettingsCategory
import com.sbro.emucorec.ui.settings.Ps3CoreSettingsSection
import com.sbro.emucorec.ui.settings.Ps3CoreSettingsSurface
import com.sbro.emucorec.ui.settings.Ps3CoreSettingsScope
import com.sbro.emucorec.ui.theme.neon.LocalNeonTheme
import com.sbro.emucorec.ui.theme.neon.NeonCrtOverlay
import com.sbro.emucorec.ui.theme.neon.neonAccentColor
import com.sbro.emucorec.ui.theme.neon.neonChipShape
import com.sbro.emucorec.ui.theme.neon.neonPillShape
import com.sbro.emucorec.ui.theme.neon.neonShape
import com.sbro.emucorec.ui.theme.neon.neonShapeCorners
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val LocalMenuScrollInset = compositionLocalOf { 7.dp }

internal data class EmulationMenuPalette(
    val panel: Color,
    val panelSoft: Color,
    val row: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color
)

@Composable
internal fun emulationMenuPalette(): EmulationMenuPalette {
    val scheme = MaterialTheme.colorScheme
    return EmulationMenuPalette(
        panel = scheme.surface.copy(alpha = 0.98f),
        panelSoft = scheme.surfaceContainerLow,
        row = scheme.surfaceVariant.copy(alpha = 0.26f),
        border = scheme.outlineVariant.copy(alpha = 0.45f),
        textPrimary = scheme.onSurface,
        textSecondary = scheme.onSurfaceVariant
    )
}

/**
 * Floating toolbar shown above the game when the user invokes the pause UI.
 * Stays compact so it doesn't cover important on-screen UI.
 */
@Composable
fun EmulationQuickBar(
    onOpenCoreHomeMenu: () -> Unit,
    onOpenMenu: () -> Unit,
    paused: Boolean = false,
    quickActionsEnabled: Boolean = true,
    onPauseToggle: (() -> Unit)? = null,
    onQuickSave: (() -> Unit)? = null,
    onQuickLoad: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val palette = emulationMenuPalette()
    Surface(
        modifier = modifier.padding(top = 12.dp),
        shape = neonShape(28.dp),
        color = palette.panel,
        contentColor = palette.textPrimary,
        border = BorderStroke(1.dp, palette.border),
        tonalElevation = 4.dp,
        shadowElevation = 12.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (onPauseToggle != null) {
                QuickBarButton(
                    accentIndex = 0,
                    icon = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    contentDescription = stringResource(
                        if (paused) R.string.emulation_resume else R.string.emulation_pause
                    ),
                    onClick = onPauseToggle,
                    palette = palette
                )
            }
            if (onQuickSave != null) {
                QuickBarButton(
                    accentIndex = 1,
                    icon = Icons.Rounded.Save,
                    contentDescription = stringResource(R.string.emulation_quickbar_quick_save),
                    onClick = onQuickSave,
                    enabled = quickActionsEnabled,
                    palette = palette
                )
            }
            if (onQuickLoad != null) {
                QuickBarButton(
                    accentIndex = 2,
                    icon = Icons.Rounded.Restore,
                    contentDescription = stringResource(R.string.emulation_quickbar_quick_load),
                    onClick = onQuickLoad,
                    enabled = quickActionsEnabled,
                    palette = palette
                )
            }
            QuickBarButton(
                accentIndex = 3,
                icon = Icons.Rounded.Home,
                contentDescription = stringResource(R.string.emulation_quickbar_home_menu),
                onClick = onOpenCoreHomeMenu,
                palette = palette
            )
            QuickBarButton(
                accentIndex = 4,
                icon = Icons.Rounded.Tune,
                contentDescription = stringResource(R.string.emulation_quickbar_open_menu),
                onClick = onOpenMenu,
                palette = palette
            )
        }
    }
}

@Composable
private fun QuickBarButton(
    accentIndex: Int,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    palette: EmulationMenuPalette
) {
    val neon = LocalNeonTheme.current
    val shape = if (neon) neonShape(14.dp) else CircleShape
    val accent = neonAccentColor(accentIndex)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(shape)
            .background(if (neon) accent.copy(alpha = 0.10f) else palette.row)
            .then(
                if (neon) Modifier.border(1.dp, accent.copy(alpha = 0.42f), shape)
                else Modifier
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (!enabled) {
                palette.textSecondary.copy(alpha = 0.4f)
            } else if (neon) {
                accent
            } else {
                palette.textPrimary
            },
            modifier = Modifier.size(22.dp)
        )
    }
}
@Composable
fun EmulationGameMenu(
    gameTitle: String,
    gameId: String,
    config: Ps3CoreConfig,
    paused: Boolean,
    sessionElapsedMs: Long,
    expandHorizontally: Boolean,
    layoutStyle: GameMenuLayoutStyle,
    physicalGamepadConnected: Boolean,
    callbacks: EmulationMenuCallbacks,
    saveStates: SaveStateMenuState = SaveStateMenuState(),
    gameIconPath: String? = null,
    gameCoverUrl: String? = null,
    // Hoisted so the menu reopens on the tab and scroll offset the player left it on.
    selectedTab: EmulationMenuTab = EmulationMenuTab.Game,
    onSelectedTab: (EmulationMenuTab) -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier
) {
    val railFocusRequesters = remember { EmulationMenuTab.entries.associateWith { androidx.compose.ui.focus.FocusRequester() } }
    val navPadding = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues()
    val rightInset by androidx.compose.animation.core.animateDpAsState(
        targetValue = navPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 220),
        label = "emulation_menu_right_inset"
    )
    val bottomInset by androidx.compose.animation.core.animateDpAsState(
        targetValue = navPadding.calculateBottomPadding(),
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 220),
        label = "emulation_menu_bottom_inset"
    )
    GameMenuLayoutHost(
        style = layoutStyle,
        modifier = modifier.fillMaxSize(),
        menuTabs = EmulationMenuTab.entries,
        selectedMenuTab = selectedTab,
        selectedTabScrollState = scrollState,
        railFocusRequesters = railFocusRequesters,
        contentBottomPadding = (if (config.enableGamepadOverlay) 36.dp else 0.dp) + bottomInset,
        rightInset = rightInset,
        sectionSpacing = 16.dp,
        onSelectTab = onSelectedTab,
        onClose = callbacks.onClose,
    ) {
        MenuHeader(gameTitle, gameId, gameIconPath, gameCoverUrl, sessionElapsedMs)
        if (selectedTab == EmulationMenuTab.Game) {
            MenuTopActions(paused = paused, callbacks = callbacks)
        }
        CompositionLocalProvider(LocalMenuScrollInset provides 18.dp) {
            MenuSelectedContent(
                selectedTab = selectedTab,
                gameId = gameId,
                config = config,
                saveStates = saveStates,
                sessionElapsedMs = sessionElapsedMs,
                physicalGamepadConnected = physicalGamepadConnected,
                callbacks = callbacks
            )
        }
    }
}

@Composable
private fun MenuSelectedContent(
    selectedTab: EmulationMenuTab,
    gameId: String,
    config: Ps3CoreConfig,
    saveStates: SaveStateMenuState,
    sessionElapsedMs: Long,
    physicalGamepadConnected: Boolean,
    callbacks: EmulationMenuCallbacks
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (selectedTab) {
            EmulationMenuTab.Game -> {
                GameTab(gameId = gameId)
            }
            EmulationMenuTab.SaveStates -> SaveStatesTab(saveStates = saveStates, callbacks = callbacks)
            EmulationMenuTab.Graphics -> {
                InGameCoreSettings(
                    Ps3CoreSettingsCategory.Graphics,
                    gameId,
                    hiddenSections = IN_GAME_HIDDEN_GRAPHICS_SECTIONS,
                )
            }
            EmulationMenuTab.Audio -> InGameCoreSettings(Ps3CoreSettingsCategory.Audio, gameId)
            EmulationMenuTab.Portals -> ToyPortalPanel()
            EmulationMenuTab.Controls -> {
                ControlsTab(config = config, callbacks = callbacks)
            }
            EmulationMenuTab.Achievements -> AchievementsTab(gameId = gameId)
            EmulationMenuTab.Gamepad -> GamepadTab(
                config = config,
                physicalGamepadConnected = physicalGamepadConnected,
                callbacks = callbacks
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
    }
}

/**
 * Overlay and Advanced stay in Settings and the game manager only: inside the game they are noise,
 * so the in-game menu no longer lists them.
 */
enum class EmulationMenuTab {
    Game,
    SaveStates,
    Graphics,
    Audio,
    Portals,
    Controls,
    Achievements,
    Gamepad
}

/**
 * Video sections that are useless while a game is already on screen: the Vulkan card (adapter,
 * exclusive fullscreen, VRAM budget) and the anaglyph matrix editor. Both stay in Settings and the
 * game manager.
 */
private val IN_GAME_HIDDEN_GRAPHICS_SECTIONS = setOf("Vulkan", "Custom Anaglyph Matrices")

/** Overlay sections that only matter while configuring, not while playing. */
private val IN_GAME_HIDDEN_OVERLAY_SECTIONS =
    setOf("Debug overlay", "Shader Loading Dialog", "Miscellaneous")

@Composable
private fun InGameCoreSettings(
    category: Ps3CoreSettingsCategory,
    gameId: String,
    additionalCategories: Set<Ps3CoreSettingsCategory> = emptySet(),
    hiddenSections: Set<String> = emptySet(),
) {
    Ps3CoreSettingsSection(
        category = category,
        additionalCategories = additionalCategories,
        scope = Ps3CoreSettingsScope.Game,
        surface = Ps3CoreSettingsSurface.InGame,
        hiddenSections = hiddenSections,
        titleId = gameId,
    )
}

private fun Modifier.horizontalBleed(horizontalPadding: Dp): Modifier = layout { measurable, constraints ->
    val bleedPx = horizontalPadding.roundToPx()
    val looseConstraints = constraints.copy(
        maxWidth = (constraints.maxWidth + bleedPx * 2).coerceAtLeast(0)
    )
    val placeable = measurable.measure(looseConstraints)
    layout(constraints.maxWidth, placeable.height) {
        placeable.placeRelative(-bleedPx, 0)
    }
}

@Composable
private fun GameTab(
    gameId: String,
) {
    // Session time already sits in the menu header, so it is not repeated here. What this tab owns
    // is the single in-game copy of the performance overlay: the stats toggle plus its layout
    // options. Everything below it in the native tree (debug overlay, shader loading dialog,
    // compilation hints) stays in Settings, and the report rows live in Settings > Advanced.
    InGameCoreSettings(
        Ps3CoreSettingsCategory.Overlay,
        gameId,
        hiddenSections = IN_GAME_HIDDEN_OVERLAY_SECTIONS,
    )
}


@Composable
private fun formatPlayDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    val locale = LocalConfiguration.current.locales[0]
    return if (hours > 0L) String.format(locale, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(locale, "%d:%02d", minutes, seconds)
}

@Composable
private fun ControlsTab(config: Ps3CoreConfig, callbacks: EmulationMenuCallbacks) {
    val context = LocalContext.current
    MenuActionRow(
        icon = Icons.Rounded.Keyboard,
        title = stringResource(R.string.settings_keyboard_mouse_section),
        subtitle = stringResource(R.string.settings_keyboard_mapping_desc),
        onClick = callbacks.onOpenKeyboardMouseSettings
    )
    MenuSection(
        title = stringResource(R.string.emulation_menu_section_touch),
        description = stringResource(R.string.emulation_menu_section_touch_desc)
    ) {
        MenuActionRow(
            icon = Icons.Rounded.Edit,
            title = stringResource(R.string.emulation_menu_edit_controls),
            subtitle = stringResource(R.string.emulation_menu_edit_controls_desc),
            onClick = callbacks.onEditControls
        )
        MenuActionRow(
            icon = if (config.enableGamepadOverlay) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            title = stringResource(
                if (config.enableGamepadOverlay) R.string.emulation_menu_hide_controls
                else R.string.emulation_menu_show_controls
            ),
            subtitle = stringResource(R.string.emulation_menu_controls_desc),
            onClick = callbacks.onControlsVisibility
        )
        MenuActionRow(
            icon = Icons.Rounded.Refresh,
            title = stringResource(R.string.emulation_menu_reset_overlay),
            subtitle = stringResource(R.string.emulation_menu_reset_overlay_desc),
            onClick = callbacks.onResetOverlay
        )
        MenuSliderRow(
            label = stringResource(R.string.settings_core_overlay_scale_label),
            description = stringResource(R.string.settings_help_overlay_scale),
            value = config.overlayScale,
            valueText = stringResource(R.string.settings_core_overlay_scale_value, config.overlayScale),
            valueRange = 0.5f..2f,
            steps = 14,
            onValueChange = callbacks.onOverlayScale
        )
        MenuSliderRow(
            label = stringResource(R.string.settings_core_overlay_opacity_label),
            description = stringResource(R.string.settings_help_overlay_opacity),
            value = config.overlayOpacity.toFloat(),
            valueText = stringResource(R.string.settings_core_overlay_opacity_value, config.overlayOpacity),
            valueRange = 10f..100f,
            steps = 8,
            onValueChange = { callbacks.onOverlayOpacity(it.roundToInt()) }
        )
    }
    MenuSection(
        title = stringResource(R.string.settings_touch_haptics),
        description = stringResource(R.string.settings_help_touch_haptics)
    ) {
        MenuToggleRow(
            label = stringResource(R.string.settings_touch_haptics),
            subtitle = stringResource(R.string.settings_help_touch_haptics),
            checked = config.touchHaptics,
            onCheckedChange = callbacks.onTouchHaptics
        )
        MenuChipRow(
            label = stringResource(R.string.settings_touch_haptics_preset),
            description = stringResource(R.string.settings_help_touch_haptics_preset),
            selected = config.touchHapticsPreset,
            options = listOf(
                Ps3CoreConfig.TOUCH_HAPTICS_PRESET_SOFT to stringResource(R.string.settings_touch_haptics_preset_soft),
                Ps3CoreConfig.TOUCH_HAPTICS_PRESET_BALANCED to stringResource(R.string.settings_touch_haptics_preset_balanced),
                Ps3CoreConfig.TOUCH_HAPTICS_PRESET_CRISP to stringResource(R.string.settings_touch_haptics_preset_crisp),
                Ps3CoreConfig.TOUCH_HAPTICS_PRESET_STRONG to stringResource(R.string.settings_touch_haptics_preset_strong)
            ),
            enabled = config.touchHaptics,
            onSelected = callbacks.onTouchHapticsPreset
        )
        MenuSliderRow(
            label = stringResource(R.string.settings_touch_haptics_strength),
            description = stringResource(R.string.settings_help_touch_haptics_strength),
            value = config.touchHapticsStrength.toFloat(),
            valueText = stringResource(R.string.settings_gamepad_percent_value, config.touchHapticsStrength),
            valueRange = 10f..100f,
            steps = 17,
            enabled = config.touchHaptics,
            onValueChange = { callbacks.onTouchHapticsStrength(it.roundToInt().coerceIn(10, 100)) }
        )
    }
    MenuSection(
        title = stringResource(R.string.settings_gyro_mode),
        description = stringResource(R.string.settings_help_gyro_mode)
    ) {
        MenuChipRow(
            label = stringResource(R.string.settings_gyro_mode),
            description = stringResource(R.string.settings_help_gyro_mode),
            selected = config.gyroMode,
            showLabel = false,
            options = listOf(
                Ps3CoreConfig.GYRO_MODE_OFF to stringResource(R.string.settings_gyro_off),
                Ps3CoreConfig.GYRO_MODE_AIM to stringResource(R.string.settings_gyro_aim),
                Ps3CoreConfig.GYRO_MODE_STEERING to stringResource(R.string.settings_gyro_steering)
            ),
            onSelected = callbacks.onGyroMode
        )
        if (config.gyroMode != Ps3CoreConfig.GYRO_MODE_OFF &&
            !AndroidGyroscopeInput.isModeAvailable(context, config.gyroMode)
        ) {
            Text(
                text = stringResource(R.string.settings_gyro_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (config.gyroMode != Ps3CoreConfig.GYRO_MODE_OFF) {
            MenuSliderRow(
                label = stringResource(R.string.settings_gyro_sensitivity),
                description = stringResource(R.string.settings_help_gyro_sensitivity),
                value = config.gyroSensitivity.toFloat(),
                valueText = stringResource(R.string.settings_gamepad_percent_value, config.gyroSensitivity),
                valueRange = 25f..300f,
                steps = 10,
                onValueChange = { callbacks.onGyroSensitivity(it.roundToInt()) }
            )
            MenuSliderRow(
                label = stringResource(R.string.settings_gyro_smoothing),
                description = stringResource(R.string.settings_help_gyro_smoothing),
                value = config.gyroSmoothing.toFloat(),
                valueText = stringResource(R.string.settings_gamepad_percent_value, config.gyroSmoothing),
                valueRange = 0f..90f,
                steps = 8,
                onValueChange = { callbacks.onGyroSmoothing(it.roundToInt()) }
            )
            MenuToggleRow(
                label = stringResource(R.string.settings_gyro_invert_x),
                subtitle = stringResource(R.string.settings_gyro_invert_x_desc),
                checked = config.gyroInvertX,
                onCheckedChange = callbacks.onGyroInvertX
            )
            if (config.gyroMode == Ps3CoreConfig.GYRO_MODE_AIM) {
                MenuToggleRow(
                    label = stringResource(R.string.settings_gyro_invert_y),
                    subtitle = stringResource(R.string.settings_gyro_invert_y_desc),
                    checked = config.gyroInvertY,
                    onCheckedChange = callbacks.onGyroInvertY
                )
            }
        }
    }
}

@Composable
private fun SaveStatesTab(saveStates: SaveStateMenuState, callbacks: EmulationMenuCallbacks) {
    val palette = emulationMenuPalette()
    val slotOrder = SaveStateRepository.SLOT_ORDER
    val selectedIndex = slotOrder.indexOf(saveStates.selectedSlot).coerceAtLeast(0)
    val selected = slotOrder[selectedIndex]
    val slot = saveStates.slots.firstOrNull { it.slot == selected }
    val hasState = slot?.exists == true
    val busy = saveStates.busy || !saveStates.canInteract
    val statusText = if (slot?.exists == true) {
        stringResource(R.string.emulation_savestate_saved_at, formatSaveStateDate(slot.timestamp))
    } else {
        stringResource(R.string.emulation_savestate_empty)
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(modifier = Modifier.fillMaxWidth(), shape = neonShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            contentColor = palette.textPrimary) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = stringResource(R.string.emulation_menu_section_savestates),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = palette.textPrimary, modifier = Modifier.weight(1f))
                SaveStateSlotSwitcher(
                    label = if (selected == SaveStateRepository.QUICK_SLOT) saveStateSlotLabel(selected) else selected.toString(),
                    canGoPrevious = selectedIndex > 0 && !busy,
                    canGoNext = selectedIndex < slotOrder.lastIndex && !busy,
                    onPrevious = { callbacks.onSaveStateSlotSelected(slotOrder[selectedIndex - 1]) },
                    onNext = { callbacks.onSaveStateSlotSelected(slotOrder[selectedIndex + 1]) }
                )
            }
            SaveStatePreview(
                slot = slot,
                modifier = Modifier.fillMaxWidth(),
                slotLabel = saveStateSlotLabel(selected),
                statusText = statusText,
                isSaving = saveStates.busy && saveStates.busySaving
            )
            }
        }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SaveStateActionButton(
                    icon = Icons.Rounded.Save,
                    contentDescription = stringResource(R.string.emulation_savestate_save_action),
                    enabled = !busy,
                    showProgress = saveStates.busy && saveStates.busySaving,
                    onClick = { callbacks.onSaveStateSave(selected) },
                    modifier = Modifier.weight(1f)
                )
                SaveStateActionButton(
                    icon = Icons.Rounded.Restore,
                    contentDescription = stringResource(R.string.emulation_savestate_load_action),
                    enabled = hasState && !busy,
                    showProgress = saveStates.busy && !saveStates.busySaving && !saveStates.busyDeleting,
                    onClick = { callbacks.onSaveStateLoad(selected) },
                    modifier = Modifier.weight(1f)
                )
                if (hasState) {
                    SaveStateDeleteButton(
                        enabled = !busy,
                        onClick = { callbacks.onSaveStateDelete(selected) }
                    )
                }
            }
    }
}

@Composable
private fun SaveStateSlotSwitcher(
    label: String,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onPrevious,
            enabled = canGoPrevious,
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.common_previous)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        IconButton(
            onClick = onNext,
            enabled = canGoNext,
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = stringResource(R.string.common_next)
            )
        }
    }
}

@Composable
private fun SaveStateActionButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    showProgress: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = emulationMenuPalette()
    val shape = neonShape(18.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick),
        shape = shape,
        color = if (enabled) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            palette.row
        },
        border = BorderStroke(1.dp, palette.textPrimary.copy(alpha = 0.05f))
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            if (showProgress) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary)
            else Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) MaterialTheme.colorScheme.primary else palette.textSecondary.copy(alpha = 0.42f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun SaveStateDeleteButton(enabled: Boolean, onClick: () -> Unit) {
    val palette = emulationMenuPalette()
    val shape = neonShape(16.dp)
    Surface(
        modifier = Modifier
            .size(52.dp)
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick),
        shape = shape,
        color = if (enabled) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        } else {
            palette.row
        },
        border = BorderStroke(
            1.dp,
            if (enabled) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.26f)
            } else {
                palette.border
            }
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = stringResource(R.string.emulation_savestate_delete_action),
                tint = if (enabled) MaterialTheme.colorScheme.error else palette.textSecondary.copy(alpha = 0.42f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun saveStateSlotLabel(slot: Int): String =
    if (slot == SaveStateRepository.QUICK_SLOT) {
        stringResource(R.string.emulation_savestate_quick_label)
    } else {
        stringResource(R.string.emulation_savestate_slot_label, slot)
    }

@Composable
private fun formatSaveStateDate(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    return java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, LocalConfiguration.current.locales[0],
    )
        .format(java.util.Date(timestamp))
}

@Composable
private fun GamepadTab(
    config: Ps3CoreConfig,
    physicalGamepadConnected: Boolean,
    callbacks: EmulationMenuCallbacks
) {
    MenuSection(
        title = stringResource(R.string.emulation_menu_section_gamepad),
        subtitle = if (physicalGamepadConnected) {
            stringResource(R.string.emulation_menu_section_gamepad_desc)
        } else {
            stringResource(R.string.emulation_menu_section_gamepad_disconnected)
        }
    ) {
        MenuSliderRow(
            label = stringResource(R.string.settings_gamepad_deadzone),
            description = stringResource(R.string.settings_help_gamepad_deadzone),
            value = config.gamepadDeadzone,
            valueText = stringResource(R.string.settings_gamepad_percent_value, (config.gamepadDeadzone * 100f).roundToInt()),
            valueRange = 0f..0.45f,
            steps = 8,
            enabled = physicalGamepadConnected,
            onValueChange = { callbacks.onGamepadDeadzone((it * 100f).roundToInt() / 100f) }
        )
        MenuSliderRow(
            label = stringResource(R.string.settings_core_analog_multiplier_label),
            description = stringResource(R.string.settings_help_analog_multiplier),
            value = config.analogMultiplier,
            valueText = stringResource(R.string.settings_core_analog_multiplier_value, config.analogMultiplier),
            valueRange = 0.5f..2f,
            steps = 14,
            enabled = physicalGamepadConnected,
            onValueChange = { callbacks.onGamepadAnalogMultiplier((it * 10f).roundToInt() / 10f) }
        )
        MenuSliderRow(
            label = stringResource(R.string.settings_gamepad_trigger_threshold),
            description = stringResource(R.string.settings_help_gamepad_trigger_threshold),
            value = config.gamepadTriggerThreshold,
            valueText = stringResource(R.string.settings_gamepad_percent_value, (config.gamepadTriggerThreshold * 100f).roundToInt()),
            valueRange = 0f..0.9f,
            steps = 8,
            enabled = physicalGamepadConnected,
            onValueChange = { callbacks.onGamepadTriggerThreshold((it * 100f).roundToInt() / 100f) }
        )
        MenuChipRow(
            label = stringResource(R.string.settings_gamepad_button_profile),
            description = stringResource(R.string.settings_help_gamepad_button_profile),
            selected = when (config.gamepadButtonProfile) {
                Ps3CoreConfig.GAMEPAD_PROFILE_SWAP_CROSS_CIRCLE -> 1
                Ps3CoreConfig.GAMEPAD_PROFILE_NINTENDO_FACE -> 2
                else -> 0
            },
            options = listOf(
                0 to stringResource(R.string.settings_gamepad_profile_standard),
                1 to stringResource(R.string.settings_gamepad_profile_swap_cross_circle),
                2 to stringResource(R.string.settings_gamepad_profile_nintendo_face)
            ),
            enabled = physicalGamepadConnected,
            onSelected = { value ->
                callbacks.onGamepadButtonProfile(
                    when (value) {
                        1 -> Ps3CoreConfig.GAMEPAD_PROFILE_SWAP_CROSS_CIRCLE
                        2 -> Ps3CoreConfig.GAMEPAD_PROFILE_NINTENDO_FACE
                        else -> Ps3CoreConfig.GAMEPAD_PROFILE_STANDARD
                    }
                )
            }
        )
        MenuToggleRow(
            label = stringResource(R.string.settings_vibration_enable),
            subtitle = stringResource(R.string.settings_help_vibration_enable),
            checked = config.gamepadVibration,
            onCheckedChange = callbacks.onGamepadVibration
        )
        MenuSliderRow(
            label = stringResource(R.string.settings_vibration_strength),
            description = stringResource(R.string.settings_help_vibration_strength),
            value = config.gamepadVibrationStrength.toFloat(),
            valueText = stringResource(R.string.settings_gamepad_percent_value, config.gamepadVibrationStrength),
            valueRange = 0f..100f,
            steps = 19,
            enabled = config.gamepadVibration,
            onValueChange = { callbacks.onGamepadVibrationStrength(it.roundToInt().coerceIn(0, 100)) }
        )
        MenuToggleRow(
            label = stringResource(R.string.settings_device_vibration_fallback),
            subtitle = stringResource(R.string.settings_help_device_vibration_fallback),
            checked = config.deviceVibrationFallback,
            enabled = config.gamepadVibration,
            onCheckedChange = callbacks.onDeviceVibrationFallback
        )
        MenuToggleRow(
            label = stringResource(R.string.settings_gamepad_swap_sticks),
            subtitle = stringResource(R.string.settings_help_gamepad_swap_sticks),
            checked = config.gamepadSwapSticks,
            enabled = physicalGamepadConnected,
            onCheckedChange = callbacks.onGamepadSwapSticks
        )
        MenuToggleRow(
            label = stringResource(R.string.game_manager_invert_left_x),
            subtitle = stringResource(R.string.settings_help_gamepad_invert_x),
            checked = config.gamepadInvertLeftX,
            enabled = physicalGamepadConnected,
            onCheckedChange = callbacks.onGamepadInvertLeftX
        )
        MenuToggleRow(
            label = stringResource(R.string.settings_gamepad_invert_left_y),
            subtitle = stringResource(R.string.settings_help_gamepad_invert_y),
            checked = config.gamepadInvertLeftY,
            enabled = physicalGamepadConnected,
            onCheckedChange = callbacks.onGamepadInvertLeftY
        )
        MenuToggleRow(
            label = stringResource(R.string.game_manager_invert_right_x),
            subtitle = stringResource(R.string.settings_help_gamepad_invert_x),
            checked = config.gamepadInvertRightX,
            enabled = physicalGamepadConnected,
            onCheckedChange = callbacks.onGamepadInvertRightX
        )
        MenuToggleRow(
            label = stringResource(R.string.settings_gamepad_invert_right_y),
            subtitle = stringResource(R.string.settings_help_gamepad_invert_y),
            checked = config.gamepadInvertRightY,
            enabled = physicalGamepadConnected,
            onCheckedChange = callbacks.onGamepadInvertRightY
        )
    }
}

@Composable
private fun AchievementsTab(gameId: String) {
    val context = LocalContext.current
    var trophySets by remember(gameId) { mutableStateOf<List<Ps3TrophySet>>(emptyList()) }
    var isLoading by remember(gameId) { mutableStateOf(true) }

    LaunchedEffect(context, gameId) {
        val repository = TrophyRepository()
        var firstLoad = true
        while (true) {
            if (firstLoad) {
                isLoading = true
            }
            trophySets = if (gameId.isBlank()) {
                emptyList()
            } else {
                withContext(Dispatchers.IO) {
                    repository.loadForTitle(context, gameId)
                }
            }
            firstLoad = false
            isLoading = false
            delay(2_500)
        }
    }

    if (isLoading) {
        MenuSection(
            title = stringResource(R.string.achievements_title),
            subtitle = stringResource(R.string.emulation_achievements_loading)
        ) {
            repeat(4) {
                AchievementMenuSkeleton()
            }
        }
        return
    }

    if (trophySets.isEmpty()) {
        MenuSection(
            title = stringResource(R.string.achievements_title),
            subtitle = stringResource(R.string.emulation_achievements_empty)
        ) {
            MenuEmptyAchievements()
        }
        return
    }

    trophySets.forEach { set ->
        MenuSection(
            title = set.setName.ifBlank { set.gameTitle.ifBlank { stringResource(R.string.achievements_title) } },
            subtitle = stringResource(R.string.achievements_progress_count, set.unlockedCount, set.trophyCount)
        ) {
            AchievementSetProgress(set = set)
            set.groups.forEach { group ->
                if (group.name.isNotBlank() && set.groups.size > 1) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = emulationMenuPalette().textSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                group.trophies.forEach { trophy ->
                    AchievementMenuRow(trophy = trophy)
                }
            }
        }
    }
}

@Composable
private fun AchievementSetProgress(set: Ps3TrophySet) {
    val palette = emulationMenuPalette()
    val progress = if (set.trophyCount > 0) set.unlockedCount / set.trophyCount.toFloat() else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(neonShape(12.dp))
            .background(palette.row)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                // Same ICON0 artwork as the header: keep 16:9 instead of cropping it square.
                modifier = Modifier
                    .width(56.dp)
                    .aspectRatio(16f / 9f),
                shape = neonShape(10.dp),
                color = palette.panelSoft,
                border = BorderStroke(1.dp, palette.border)
            ) {
                LocalImage(
                    path = set.gameIconPath,
                    contentDescription = set.gameTitle,
                    fallbackLabel = set.gameTitle,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = set.gameTitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.achievements_progress_count, set.unlockedCount, set.trophyCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = palette.border
        )
    }
}

@Composable
private fun AchievementMenuRow(trophy: Ps3Trophy) {
    val palette = emulationMenuPalette()
    val hiddenLocked = trophy.hidden && !trophy.unlocked
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(neonShape(12.dp))
            .background(palette.row)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = neonShape(10.dp),
            color = palette.panelSoft,
            border = BorderStroke(1.dp, palette.border)
        ) {
            if (hiddenLocked) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = palette.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                LocalImage(
                    path = trophy.iconPath,
                    contentDescription = trophy.name,
                    fallbackLabel = trophy.name.ifBlank { "?" },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = if (hiddenLocked) stringResource(R.string.achievements_hidden_trophy) else trophy.name.ifBlank { stringResource(R.string.achievements_trophy) },
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (trophy.unlocked) palette.textPrimary else palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (hiddenLocked) stringResource(R.string.achievements_hidden_trophy_body) else trophy.detail,
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (trophy.unlocked) {
                AchievementUnlockedMark()
            }
            AchievementGradeBadge(grade = trophy.grade, unlocked = trophy.unlocked)
        }
    }
}

@Composable
private fun AchievementUnlockedMark() {
    val color = Color(0xFF34D27A)
    Surface(
        modifier = Modifier.size(22.dp),
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.38f))
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun AchievementGradeBadge(
    grade: Ps3TrophyGrade,
    unlocked: Boolean
) {
    val color = if (!unlocked) {
        emulationMenuPalette().textSecondary.copy(alpha = 0.72f)
    } else {
        when (grade) {
            Ps3TrophyGrade.Platinum -> Color(0xFF7AD8FF)
            Ps3TrophyGrade.Gold -> Color(0xFFE1AA28)
            Ps3TrophyGrade.Silver -> Color(0xFFB9C1CB)
            Ps3TrophyGrade.Bronze -> Color(0xFFB8794A)
            Ps3TrophyGrade.Unknown -> MaterialTheme.colorScheme.primary
        }
    }
    Surface(
        modifier = Modifier.size(34.dp),
        shape = CircleShape,
        color = color.copy(alpha = if (unlocked) 0.18f else 0.08f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = when (grade) {
                    Ps3TrophyGrade.Platinum -> "P"
                    Ps3TrophyGrade.Gold -> "G"
                    Ps3TrophyGrade.Silver -> "S"
                    Ps3TrophyGrade.Bronze -> "B"
                    Ps3TrophyGrade.Unknown -> "-"
                },
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = color
            )
        }
    }
}

@Composable
private fun AchievementMenuSkeleton() {
    val palette = emulationMenuPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(neonShape(12.dp))
            .background(palette.row)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(neonShape(10.dp))
                .background(palette.border)
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(12.dp)
                    .clip(neonPillShape())
                    .background(palette.border)
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.48f)
                    .height(10.dp)
                    .clip(neonPillShape())
                    .background(palette.border.copy(alpha = 0.72f))
            )
        }
    }
}

@Composable
private fun MenuEmptyAchievements() {
    val palette = emulationMenuPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(neonShape(12.dp))
            .background(palette.row)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.EmojiEvents,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = stringResource(R.string.emulation_achievements_empty_body),
            style = MaterialTheme.typography.bodySmall,
            color = palette.textSecondary
        )
    }
}

@Composable
private fun MenuTopActions(paused: Boolean, callbacks: EmulationMenuCallbacks) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) {
            GameMenuButton(
                icon = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                text = stringResource(if (paused) R.string.emulation_resume else R.string.emulation_pause),
                onClick = callbacks.onPauseToggle,
                containerColor = if (!paused) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.46f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            )
        }
        Box(Modifier.weight(1f)) {
            GameMenuButton(
                icon = Icons.AutoMirrored.Rounded.ExitToApp,
                text = stringResource(R.string.emulation_menu_exit_game),
                onClick = callbacks.onExit,
                isDestructive = true,
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f)
            )
        }
    }
}

/** Callbacks bag — keeps `EmulationGameMenu` signature compact and testable. */
data class EmulationMenuCallbacks(
    val onPauseToggle: () -> Unit,
    val onExit: () -> Unit,
    val onEditControls: () -> Unit,
    val onControlsVisibility: () -> Unit,
    val onResetOverlay: () -> Unit,
    val onOverlayScale: (Float) -> Unit,
    val onOverlayOpacity: (Int) -> Unit,
    val onTouchHaptics: (Boolean) -> Unit,
    val onTouchHapticsPreset: (Int) -> Unit,
    val onTouchHapticsStrength: (Int) -> Unit,
    val onGyroMode: (Int) -> Unit,
    val onGyroSensitivity: (Int) -> Unit,
    val onGyroSmoothing: (Int) -> Unit,
    val onGyroInvertX: (Boolean) -> Unit,
    val onGyroInvertY: (Boolean) -> Unit,
    val onGamepadDeadzone: (Float) -> Unit,
    val onGamepadAnalogMultiplier: (Float) -> Unit,
    val onGamepadTriggerThreshold: (Float) -> Unit,
    val onGamepadButtonProfile: (String) -> Unit,
    val onGamepadVibration: (Boolean) -> Unit,
    val onGamepadVibrationStrength: (Int) -> Unit,
    val onDeviceVibrationFallback: (Boolean) -> Unit,
    val onGamepadSwapSticks: (Boolean) -> Unit,
    val onGamepadInvertLeftX: (Boolean) -> Unit,
    val onGamepadInvertLeftY: (Boolean) -> Unit,
    val onGamepadInvertRightX: (Boolean) -> Unit,
    val onGamepadInvertRightY: (Boolean) -> Unit,
    val onOpenKeyboardMouseSettings: () -> Unit = {},
    val onSaveStateSlotSelected: (Int) -> Unit = {},
    val onSaveStateSave: (Int) -> Unit = {},
    val onSaveStateLoad: (Int) -> Unit = {},
    val onSaveStateDelete: (Int) -> Unit = {},
    val onClose: () -> Unit = {},
)

/** Slot list state rendered by the save-state tab and the quick bar. */
data class SaveStateMenuState(
    val slots: List<SaveStateSlot> = emptyList(),
    val selectedSlot: Int = SaveStateRepository.QUICK_SLOT,
    val busy: Boolean = false,
    val busySaving: Boolean = false,
    val canInteract: Boolean = true,
    val busyDeleting: Boolean = false
)


@Composable
private fun MenuHeader(
    gameTitle: String,
    gameId: String,
    gameIconPath: String?,
    gameCoverUrl: String?,
    sessionElapsedMs: Long
) {
    val displayTitle = gameTitle.ifBlank {
        gameId.ifBlank { stringResource(R.string.emulation_menu_unknown_game) }
    }
    val palette = emulationMenuPalette()
    Surface(
        shape = neonShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        contentColor = palette.textPrimary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(
                    GradientStart.copy(alpha = 0.14f), GradientEnd.copy(alpha = 0.08f)
                )))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Portrait box-art card, matching X: the catalog cover fitted inside a
            // cover-ratio frame, with ICON0 only as the local fallback.
            Box(
                modifier = Modifier
                    .width(74.dp)
                    .aspectRatio(COVER_ART_ASPECT_RATIO)
                    .clip(neonShape(14.dp))
            ) {
                if (!gameCoverUrl.isNullOrBlank()) {
                    UrlImage(
                        imageUrl = gameCoverUrl,
                        contentDescription = displayTitle,
                        fallbackLabel = displayTitle,
                        fallbackPath = gameIconPath,
                        modifier = Modifier.fillMaxSize(),
                        decodeEdgePx = 240,
                        contentScale = ContentScale.Fit
                    )
                } else {
                    LocalImage(
                        path = gameIconPath,
                        contentDescription = displayTitle,
                        fallbackLabel = displayTitle,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = gameId.ifBlank { stringResource(R.string.emulation_menu_subtitle) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Surface(
                        shape = neonPillShape(),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                    ) {
                        Text(
                            text = stringResource(R.string.emulation_play_time_badge, formatPlayDuration(sessionElapsedMs)),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MenuSection(
    title: String,
    subtitle: String = "",
    description: String? = null,
    content: @Composable () -> Unit
) {
    val palette = emulationMenuPalette()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                // X's sidebar label: uppercase accent caption, tight tracking.
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            description?.takeIf(String::isNotBlank)?.let {
                SettingHelpButton(title = title, description = it)
            }
        }
        if (subtitle.isNotBlank()) {
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = palette.textSecondary)
        }
        content()
    }
}

@Composable
private fun MenuSettingLabel(
    label: String,
    description: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    // X sets choice/slider captions in labelLarge Bold but keeps toggle captions in
    // bodyMedium Medium, so the same label helper has to offer both.
    strong: Boolean = false,
) {
    val palette = emulationMenuPalette()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = if (strong) {
                MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
            } else {
                MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
            },
            color = (if (strong) MaterialTheme.colorScheme.onSurfaceVariant else palette.textPrimary)
                .copy(alpha = if (enabled) 1f else 0.48f),
            modifier = Modifier.weight(1f, fill = false)
        )
        description?.takeIf(String::isNotBlank)?.let {
            SettingHelpButton(title = label, description = it)
        }
    }
}

@Composable
private fun MenuToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    val palette = emulationMenuPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(neonShape(16.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, palette.textPrimary.copy(alpha = 0.05f), neonShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MenuSettingLabel(label, subtitle, Modifier.weight(1f), enabled)
        Switch(colors = appSwitchColors(), checked = checked, enabled = enabled,
            onCheckedChange = null, modifier = Modifier.scale(0.85f))
    }
}

@Composable
private fun MenuSliderRow(
    label: String,
    value: Float,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
    description: String? = null,
    onValueChange: (Float) -> Unit
) {
    val palette = emulationMenuPalette()
    var pendingValue by remember(label) { mutableFloatStateOf(value) }
    LaunchedEffect(value) { pendingValue = value }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MenuSettingLabel(label, description, Modifier.weight(1f), enabled, strong = true)
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                color = palette.textSecondary,
                modifier = Modifier.widthIn(max = 140.dp),
                maxLines = 2
            )
        }
        Slider(colors = appSliderColors(),
            value = pendingValue,
            onValueChange = { pendingValue = it },
            onValueChangeFinished = { onValueChange(pendingValue) },
            enabled = enabled,
            valueRange = valueRange,
            steps = steps
        )
    }
}

@Composable
internal fun MenuChipRow(
    label: String,
    selected: Int,
    options: List<Pair<Int, String>>,
    enabled: Boolean = true,
    description: String? = null,
    showLabel: Boolean = true,
    onSelected: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showLabel) MenuSettingLabel(label, description, Modifier.fillMaxWidth(), enabled, strong = true)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalBleed(LocalMenuScrollInset.current)
                .horizontalScroll(rememberScrollState()).padding(horizontal = LocalMenuScrollInset.current),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (value, text) ->
                FilterChip(
                    shape = neonChipShape(),
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    enabled = enabled,
                    colors = appFilterChipColors(),
                    label = {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun MenuActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true
) {
    val palette = emulationMenuPalette()
    val shape = neonShape(16.dp)
    val containerColor = if (destructive) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val iconTint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick),
        shape = shape,
        color = containerColor,
        contentColor = palette.textPrimary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (subtitle.isNotBlank()) SettingHelpButton(title = title, description = subtitle)
        }
    }
}
