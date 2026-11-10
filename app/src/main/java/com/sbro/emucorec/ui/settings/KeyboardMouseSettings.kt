package com.sbro.emucorec.ui.settings

import com.sbro.emucorec.ui.common.appSwitchColors

import android.view.KeyEvent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.sbro.emucorec.ui.common.appFilterChipColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sbro.emucorec.R
import com.sbro.emucorec.core.input.KeyboardMouseBindings
import com.sbro.emucorec.core.input.KeyboardMouseManager
import com.sbro.emucorec.data.AppPreferences
import com.sbro.emucorec.data.KeyboardMouseSettings
import com.sbro.emucorec.ui.common.AppAlertDialog
import com.sbro.emucorec.ui.common.ImmersiveDialogWindow
import com.sbro.emucorec.ui.common.SectionCard
import com.sbro.emucorec.ui.common.SettingHelpButton
import com.sbro.emucorec.ui.common.SettingsStyledDialog
import com.sbro.emucorec.ui.theme.neon.neonChipShape
import com.sbro.emucorec.ui.theme.neon.neonShape
import kotlinx.coroutines.launch

private enum class KeyboardMouseMappingSource {
    KEYBOARD,
    MOUSE
}

@Composable
fun GlobalKeyboardMouseSettingsSection(showTitle: Boolean = true) {
    val context = LocalContext.current
    val preferences = remember(context) { AppPreferences(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val settings by KeyboardMouseManager.globalSettings.collectAsState()
    val connection by KeyboardMouseManager.connection.collectAsState()

    KeyboardMouseSettingsContent(
        settings = settings,
        onSettingsChange = { updated ->
            scope.launch { preferences.setKeyboardMouseSettings(updated) }
        },
        connection = connection,
        showTitle = showTitle,
        contextNote = stringResource(R.string.settings_keyboard_mouse_global_note)
    )
}

@Composable
fun PerGameKeyboardMouseSettingsSection(
    override: KeyboardMouseSettings?,
    globalSettings: KeyboardMouseSettings,
    onOverrideChange: (KeyboardMouseSettings?) -> Unit,
    showTitle: Boolean = true
) {
    val connection by KeyboardMouseManager.connection.collectAsState()
    KeyboardMouseSettingsContent(
        settings = override ?: globalSettings,
        onSettingsChange = onOverrideChange,
        connection = connection,
        showTitle = showTitle,
        contextNote = stringResource(
            if (override == null) {
                R.string.settings_keyboard_mouse_global_note
            } else {
                R.string.settings_keyboard_mouse_game_desc
            }
        ),
        onUseGlobal = if (override != null) {
            { onOverrideChange(null) }
        } else {
            null
        }
    )
}

@Composable
fun KeyboardMouseSettingsDialog(
    settings: KeyboardMouseSettings,
    onSettingsChange: (KeyboardMouseSettings) -> Unit,
    onDismiss: () -> Unit,
    onUseGlobal: (() -> Unit)? = null
) {
    val connection by KeyboardMouseManager.connection.collectAsState()
    SettingsStyledDialog(
        title = stringResource(R.string.settings_keyboard_mouse_section),
        eyebrow = stringResource(R.string.app_name_emucorec),
        icon = Icons.Rounded.Keyboard,
        onDismissRequest = onDismiss,
        edgeToEdgeContent = true
    ) {
        // Shown over a running game: keep the system bars hidden inside this dialog's window.
        ImmersiveDialogWindow()
        KeyboardMouseSettingsContent(
            settings = settings,
            onSettingsChange = onSettingsChange,
            connection = connection,
            showTitle = false,
            contextNote = stringResource(R.string.settings_keyboard_mouse_game_desc),
            onUseGlobal = onUseGlobal,
            horizontalPadding = 16.dp
        )
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_close))
        }
    }
}

@Composable
private fun KeyboardMouseSettingsContent(
    settings: KeyboardMouseSettings,
    onSettingsChange: (KeyboardMouseSettings) -> Unit,
    connection: KeyboardMouseManager.ConnectionState,
    showTitle: Boolean,
    contextNote: String,
    onUseGlobal: (() -> Unit)? = null,
    horizontalPadding: Dp = 0.dp
) {
    var mappingSource by remember { mutableStateOf<KeyboardMouseMappingSource?>(null) }

    val content: @Composable () -> Unit = {
        KeyboardMouseToggleRow(
            icon = Icons.Rounded.Keyboard,
            title = stringResource(R.string.settings_keyboard_input),
            subtitle = stringResource(R.string.settings_keyboard_input_desc),
            checked = settings.keyboardEnabled,
            horizontalPadding = horizontalPadding,
            onCheckedChange = { enabled ->
                onSettingsChange(settings.copy(keyboardEnabled = enabled))
            }
        )
        if (settings.keyboardEnabled) {
            KeyboardMouseActionRow(
                icon = Icons.Rounded.Keyboard,
                title = stringResource(R.string.settings_keyboard_mapping_title),
                subtitle = stringResource(R.string.settings_keyboard_mapping_desc),
                horizontalPadding = horizontalPadding,
                onClick = { mappingSource = KeyboardMouseMappingSource.KEYBOARD }
            )
        }
        KeyboardMouseToggleRow(
            icon = Icons.Rounded.Mouse,
            title = stringResource(R.string.settings_mouse_input),
            subtitle = stringResource(R.string.settings_mouse_input_desc),
            checked = settings.mouseEnabled,
            horizontalPadding = horizontalPadding,
            onCheckedChange = { enabled ->
                onSettingsChange(settings.copy(mouseEnabled = enabled))
            }
        )
        if (settings.mouseEnabled) {
            KeyboardMouseActionRow(
                icon = Icons.Rounded.Mouse,
                title = stringResource(R.string.settings_mouse_mapping_title),
                subtitle = stringResource(R.string.settings_mouse_mapping_desc),
                horizontalPadding = horizontalPadding,
                onClick = { mappingSource = KeyboardMouseMappingSource.MOUSE }
            )
            KeyboardMouseToggleRow(
                icon = Icons.Rounded.Mouse,
                title = stringResource(R.string.settings_mouse_capture),
                subtitle = stringResource(R.string.settings_mouse_capture_desc),
                checked = settings.mouseCaptureCursor,
                horizontalPadding = horizontalPadding,
                onCheckedChange = { enabled ->
                    onSettingsChange(settings.copy(mouseCaptureCursor = enabled))
                }
            )
            KeyboardMouseToggleRow(
                icon = Icons.Rounded.Mouse,
                title = stringResource(R.string.settings_mouse_left_handed),
                subtitle = stringResource(R.string.settings_mouse_left_handed_desc),
                checked = settings.mouseLeftHanded,
                horizontalPadding = horizontalPadding,
                onCheckedChange = { enabled ->
                    onSettingsChange(settings.copy(mouseLeftHanded = enabled))
                }
            )
            KeyboardMouseChoiceSection(
                title = stringResource(R.string.settings_mouse_wheel),
                options = listOf(
                    AppPreferences.MOUSE_WHEEL_OFF to stringResource(R.string.settings_mouse_wheel_off),
                    AppPreferences.MOUSE_WHEEL_DPAD to stringResource(R.string.settings_mouse_wheel_dpad),
                    AppPreferences.MOUSE_WHEEL_TRIGGERS to stringResource(R.string.settings_mouse_wheel_triggers),
                    AppPreferences.MOUSE_WHEEL_LEFT_STICK to stringResource(R.string.settings_mouse_wheel_left_stick),
                    AppPreferences.MOUSE_WHEEL_RIGHT_STICK to stringResource(R.string.settings_mouse_wheel_right_stick),
                    AppPreferences.MOUSE_WHEEL_SHOULDERS to stringResource(R.string.settings_mouse_wheel_shoulders)
                ),
                selectedValue = settings.mouseWheelMode,
                helpText = stringResource(R.string.settings_mouse_wheel_desc),
                horizontalPadding = horizontalPadding,
                onSelect = { mode ->
                    onSettingsChange(settings.copy(mouseWheelMode = mode))
                }
            )
            if (settings.mouseWheelMode != AppPreferences.MOUSE_WHEEL_OFF) {
                KeyboardMouseSliderItem(
                    title = stringResource(R.string.settings_mouse_wheel_pulse),
                    value = settings.mouseWheelPulseMs,
                    range = AppPreferences.MOUSE_WHEEL_PULSE_MIN_MS.toFloat()..
                        AppPreferences.MOUSE_WHEEL_PULSE_MAX_MS.toFloat(),
                    valueFormatRes = R.string.settings_keyboard_mouse_milliseconds_value,
                    helpText = stringResource(R.string.settings_mouse_wheel_pulse_desc),
                    horizontalPadding = horizontalPadding,
                    onValueChange = { value ->
                        onSettingsChange(settings.copy(mouseWheelPulseMs = value))
                    }
                )
            }
            KeyboardMouseChoiceSection(
                title = stringResource(R.string.settings_mouse_aim_mode),
                options = listOf(
                    AppPreferences.MOUSE_AIM_OFF to stringResource(R.string.settings_mouse_aim_off),
                    AppPreferences.MOUSE_AIM_RIGHT_STICK to stringResource(R.string.settings_mouse_aim_right_stick),
                    AppPreferences.MOUSE_AIM_LEFT_STICK to stringResource(R.string.settings_mouse_aim_left_stick)
                ),
                selectedValue = settings.mouseAimMode,
                helpText = stringResource(R.string.settings_mouse_aim_mode_desc),
                horizontalPadding = horizontalPadding,
                onSelect = { mode ->
                    onSettingsChange(settings.copy(mouseAimMode = mode))
                }
            )
            if (settings.mouseAimMode != KeyboardMouseBindings.MOUSE_AIM_OFF) {
                KeyboardMouseSliderItem(
                    title = stringResource(R.string.settings_mouse_sensitivity),
                    value = settings.mouseSensitivity,
                    range = AppPreferences.MOUSE_SENSITIVITY_MIN.toFloat()..
                        AppPreferences.MOUSE_SENSITIVITY_MAX.toFloat(),
                    helpText = stringResource(R.string.settings_mouse_sensitivity_desc),
                    horizontalPadding = horizontalPadding,
                    onValueChange = { value ->
                        onSettingsChange(settings.copy(mouseSensitivity = value))
                    }
                )
                KeyboardMouseSliderItem(
                    title = stringResource(R.string.settings_mouse_vertical_sensitivity),
                    value = settings.mouseVerticalSensitivity,
                    helpText = stringResource(R.string.settings_mouse_vertical_sensitivity_desc),
                    range = AppPreferences.MOUSE_VERTICAL_SENSITIVITY_MIN.toFloat()..
                        AppPreferences.MOUSE_VERTICAL_SENSITIVITY_MAX.toFloat(),
                    horizontalPadding = horizontalPadding,
                    onValueChange = { value ->
                        onSettingsChange(settings.copy(mouseVerticalSensitivity = value))
                    }
                )
                KeyboardMouseSliderItem(
                    title = stringResource(R.string.settings_mouse_smoothing),
                    value = settings.mouseSmoothing,
                    range = AppPreferences.MOUSE_SMOOTHING_MIN.toFloat()..
                        AppPreferences.MOUSE_SMOOTHING_MAX.toFloat(),
                    helpText = stringResource(R.string.settings_mouse_smoothing_desc),
                    horizontalPadding = horizontalPadding,
                    onValueChange = { value ->
                        onSettingsChange(settings.copy(mouseSmoothing = value))
                    }
                )
                KeyboardMouseSliderItem(
                    title = stringResource(R.string.settings_mouse_deadzone),
                    value = settings.mouseAimDeadzone,
                    helpText = stringResource(R.string.settings_mouse_deadzone_desc),
                    range = AppPreferences.MOUSE_DEADZONE_MIN.toFloat()..
                        AppPreferences.MOUSE_DEADZONE_MAX.toFloat(),
                    horizontalPadding = horizontalPadding,
                    onValueChange = { value ->
                        onSettingsChange(settings.copy(mouseAimDeadzone = value))
                    }
                )
                KeyboardMouseToggleRow(
                    icon = Icons.Rounded.Mouse,
                    title = stringResource(R.string.settings_mouse_invert_x),
                    subtitle = stringResource(R.string.settings_mouse_invert_x_desc),
                    checked = settings.mouseInvertX,
                    horizontalPadding = horizontalPadding,
                    onCheckedChange = { enabled ->
                        onSettingsChange(settings.copy(mouseInvertX = enabled))
                    }
                )
                KeyboardMouseToggleRow(
                    icon = Icons.Rounded.Mouse,
                    title = stringResource(R.string.settings_mouse_invert_y),
                    subtitle = stringResource(R.string.settings_mouse_invert_y_desc),
                    checked = settings.mouseInvertY,
                    horizontalPadding = horizontalPadding,
                    onCheckedChange = { enabled ->
                        onSettingsChange(settings.copy(mouseInvertY = enabled))
                    }
                )
            }
        }
        if (settings.keyboardEnabled || settings.mouseEnabled) {
            KeyboardMouseInlineNote(text = contextNote, horizontalPadding = horizontalPadding)
        }
        if (connection.keyboardConnected) {
            KeyboardMouseInlineNote(
                text = stringResource(R.string.settings_keyboard_connected),
                horizontalPadding = horizontalPadding
            )
        }
        if (connection.mouseConnected) {
            KeyboardMouseInlineNote(
                text = stringResource(R.string.settings_mouse_connected),
                horizontalPadding = horizontalPadding
            )
        }
        KeyboardMouseActionRow(
            icon = Icons.Rounded.SettingsSuggest,
            title = stringResource(R.string.settings_keyboard_mouse_mapping_reset_title),
            subtitle = stringResource(R.string.settings_keyboard_mouse_mapping_reset_desc),
            horizontalPadding = horizontalPadding,
            onClick = {
                onSettingsChange(
                    settings.copy(
                        keyboardBindingsByPad = settings.keyboardBindingsByPad -
                            settings.keyboardTargetPad,
                        mouseBindingsByPad = settings.mouseBindingsByPad -
                            settings.mouseTargetPad
                    )
                )
            }
        )
        onUseGlobal?.let { useGlobal ->
            KeyboardMouseActionRow(
                icon = Icons.Rounded.Restore,
                title = stringResource(R.string.settings_keyboard_mouse_use_global),
                subtitle = stringResource(R.string.settings_keyboard_mouse_use_global_desc),
                horizontalPadding = horizontalPadding,
                onClick = useGlobal
            )
        }
    }

    if (showTitle) {
        SectionCard(
            title = stringResource(R.string.settings_keyboard_mouse_section),
            contentPadding = PaddingValues(14.dp)
        ) {
            content()
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }

    mappingSource?.let { source ->
        KeyboardMouseMappingDialog(
            settings = settings,
            onSettingsChange = onSettingsChange,
            initialSource = source,
            onDismiss = { mappingSource = null }
        )
    }
}

@Composable
private fun KeyboardMouseMappingDialog(
    settings: KeyboardMouseSettings,
    onSettingsChange: (KeyboardMouseSettings) -> Unit,
    initialSource: KeyboardMouseMappingSource,
    onDismiss: () -> Unit
) {
    val capture by KeyboardMouseManager.bindingCapture.collectAsState()
    var source by rememberSaveable { mutableIntStateOf(initialSource.ordinal) }
    DisposableEffect(Unit) {
        onDispose { KeyboardMouseManager.cancelBindingCapture() }
    }
    val mappingSource = if (source == KeyboardMouseMappingSource.MOUSE.ordinal) {
        KeyboardMouseMappingSource.MOUSE
    } else {
        KeyboardMouseMappingSource.KEYBOARD
    }
    val padIndex = if (mappingSource == KeyboardMouseMappingSource.MOUSE) {
        settings.mouseTargetPad.coerceIn(0, 1)
    } else {
        settings.keyboardTargetPad.coerceIn(0, 1)
    }
    val customBindings = if (mappingSource == KeyboardMouseMappingSource.MOUSE) {
        settings.mouseBindingsByPad[padIndex].orEmpty()
    } else {
        settings.keyboardBindingsByPad[padIndex].orEmpty()
    }

    SettingsStyledDialog(
        title = stringResource(
            if (mappingSource == KeyboardMouseMappingSource.MOUSE) {
                R.string.settings_mouse_mapping_title
            } else {
                R.string.settings_keyboard_mapping_title
            }
        ),
        eyebrow = stringResource(R.string.app_name_emucorec),
        icon = if (mappingSource == KeyboardMouseMappingSource.MOUSE) {
            Icons.Rounded.Mouse
        } else {
            Icons.Rounded.Keyboard
        },
        onDismissRequest = onDismiss,
        edgeToEdgeContent = true
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(colors = appFilterChipColors(),
                shape = neonChipShape(),
                selected = mappingSource == KeyboardMouseMappingSource.KEYBOARD,
                onClick = { source = KeyboardMouseMappingSource.KEYBOARD.ordinal },
                label = { Text(stringResource(R.string.settings_keyboard_mouse_source_keyboard)) }
            )
            FilterChip(colors = appFilterChipColors(),
                shape = neonChipShape(),
                selected = mappingSource == KeyboardMouseMappingSource.MOUSE,
                onClick = { source = KeyboardMouseMappingSource.MOUSE.ordinal },
                label = { Text(stringResource(R.string.settings_keyboard_mouse_source_mouse)) }
            )
        }
        Text(
            text = stringResource(
                if (mappingSource == KeyboardMouseMappingSource.MOUSE) {
                    R.string.settings_mouse_mapping_desc
                } else {
                    R.string.settings_keyboard_mapping_desc
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            KeyboardMouseBindings.mappableActionIds.forEach { actionId ->
                val resolved = if (mappingSource == KeyboardMouseMappingSource.MOUSE) {
                    KeyboardMouseBindings.resolveMouseBinding(actionId, customBindings)
                } else {
                    KeyboardMouseBindings.resolveKeyboardBinding(actionId, customBindings)
                }
                val hasCustom = customBindings.containsKey(actionId)
                val value = when {
                    resolved == null -> stringResource(R.string.settings_not_set)
                    mappingSource == KeyboardMouseMappingSource.MOUSE ->
                        localizedMouseButtonLabel(resolved)
                    else -> localizedKeyboardKeyLabel(resolved)
                }
                KeyboardMouseBindingRow(
                    title = keyboardMouseActionLabel(actionId),
                    value = value,
                    autoLabel = if (!hasCustom && resolved != null) {
                        stringResource(R.string.settings_gamepad_mapping_auto_format)
                    } else {
                        null
                    },
                    icon = if (mappingSource == KeyboardMouseMappingSource.MOUSE) {
                        Icons.Rounded.Mouse
                    } else {
                        Icons.Rounded.Keyboard
                    },
                    horizontalPadding = 16.dp,
                    onBindClick = {
                        if (mappingSource == KeyboardMouseMappingSource.MOUSE) {
                            KeyboardMouseManager.startMouseBindingCapture(actionId) { button ->
                                val updated = KeyboardMouseBindings.assign(
                                    customBindings,
                                    actionId,
                                    button
                                )
                                onSettingsChange(
                                    settings.copy(
                                        mouseBindingsByPad = settings.mouseBindingsByPad +
                                            (padIndex to updated)
                                    )
                                )
                            }
                        } else {
                            KeyboardMouseManager.startKeyboardBindingCapture(actionId) { keyCode ->
                                val updated = KeyboardMouseBindings.assign(
                                    customBindings,
                                    actionId,
                                    keyCode
                                )
                                onSettingsChange(
                                    settings.copy(
                                        keyboardBindingsByPad = settings.keyboardBindingsByPad +
                                            (padIndex to updated)
                                    )
                                )
                            }
                        }
                    },
                    onClearClick = if (hasCustom || resolved != null) {
                        {
                            val updated = KeyboardMouseBindings.clear(customBindings, actionId)
                            onSettingsChange(
                                if (mappingSource == KeyboardMouseMappingSource.MOUSE) {
                                    settings.copy(
                                        mouseBindingsByPad = settings.mouseBindingsByPad +
                                            (padIndex to updated)
                                    )
                                } else {
                                    settings.copy(
                                        keyboardBindingsByPad = settings.keyboardBindingsByPad +
                                            (padIndex to updated)
                                    )
                                }
                            )
                        }
                    } else {
                        null
                    }
                )
            }
        }
        KeyboardMouseBindingRow(
            title = stringResource(R.string.settings_keyboard_mouse_mapping_reset_title),
            value = stringResource(R.string.settings_keyboard_mouse_mapping_reset_desc),
            autoLabel = null,
            icon = Icons.Rounded.SettingsSuggest,
            valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
            horizontalPadding = 16.dp,
            onBindClick = {
                onSettingsChange(
                    if (mappingSource == KeyboardMouseMappingSource.MOUSE) {
                        settings.copy(mouseBindingsByPad = settings.mouseBindingsByPad - padIndex)
                    } else {
                        settings.copy(keyboardBindingsByPad = settings.keyboardBindingsByPad - padIndex)
                    }
                )
            },
            onClearClick = null
        )
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.common_close))
        }
    }

    val activeCapture = capture
    if (activeCapture != null) {
        AppAlertDialog(
            onDismissRequest = { KeyboardMouseManager.cancelBindingCapture() },
            confirmButton = {
                TextButton(onClick = { KeyboardMouseManager.cancelBindingCapture() }) {
                    Text(stringResource(R.string.common_close))
                }
            },
            title = {
                Text(
                    stringResource(
                        if (activeCapture.source == KeyboardMouseManager.BindingSource.MOUSE) {
                            R.string.settings_mouse_mapping_listening_title
                        } else {
                            R.string.settings_keyboard_mapping_listening_title
                        }
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        if (activeCapture.source == KeyboardMouseManager.BindingSource.MOUSE) {
                            R.string.settings_mouse_mapping_listening_desc
                        } else {
                            R.string.settings_keyboard_mapping_listening_desc
                        }
                    )
                )
            }
        )
    }
}

@Composable
private fun KeyboardMouseToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    horizontalPadding: Dp,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .clip(neonShape(18.dp))
            .clickable { onCheckedChange(!checked) },
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(icon = icon)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp, end = 16.dp)
            ) {
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
            Switch(colors = appSwitchColors(), checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun KeyboardMouseActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    horizontalPadding: Dp,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconTile(icon = icon)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyboardMouseChoiceSection(
    title: String,
    options: List<Pair<Int, String>>,
    selectedValue: Int,
    horizontalPadding: Dp,
    helpText: String? = null,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            helpText?.let {
                SettingHelpButton(title = title, description = it)
            }
        }
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (value, label) ->
                FilterChip(
                    shape = neonChipShape(),
                    selected = selectedValue == value,
                    onClick = { onSelect(value) },
                    colors = appFilterChipColors(),
                    label = { Text(label) }
                )
            }
        }
    }
}

@Composable
private fun KeyboardMouseSliderItem(
    title: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    valueFormatRes: Int = R.string.settings_gamepad_percent_value,
    horizontalPadding: Dp,
    helpText: String? = null,
    onValueChange: (Int) -> Unit
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            helpText?.let {
                SettingHelpButton(title = title, description = it)
            }
            Text(
                text = stringResource(valueFormatRes, sliderValue.toInt()),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onValueChange(sliderValue.toInt()) },
            valueRange = range,
            steps = 0,
            modifier = Modifier.padding(horizontal = horizontalPadding),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun KeyboardMouseInlineNote(text: String, horizontalPadding: Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = horizontalPadding)
    )
}

@Composable
private fun KeyboardMouseBindingRow(
    title: String,
    value: String,
    autoLabel: String?,
    icon: ImageVector,
    horizontalPadding: Dp,
    valueColor: Color? = null,
    onBindClick: () -> Unit,
    onClearClick: (() -> Unit)?
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
        shape = neonChipShape(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        onClick = onBindClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconTile(icon = icon)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = valueColor ?: MaterialTheme.colorScheme.primary
                    )
                    autoLabel?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            onClearClick?.let {
                TextButton(onClick = it) {
                    Text(stringResource(R.string.settings_gamepad_mapping_clear))
                }
            }
        }
    }
}

@Composable
private fun IconTile(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(neonShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun keyboardMouseActionLabel(actionId: String): String = when (actionId) {
    "cross" -> "\u2715"
    "circle" -> "\u25cb"
    "square" -> "\u25a1"
    "triangle" -> "\u25b3"
    "l1" -> stringResource(R.string.controls_button_l1)
    "l2" -> stringResource(R.string.controls_button_l2)
    "l3" -> stringResource(R.string.controls_button_l3)
    "r1" -> stringResource(R.string.controls_button_r1)
    "r2" -> stringResource(R.string.controls_button_r2)
    "r3" -> stringResource(R.string.controls_button_r3)
    "select" -> stringResource(R.string.controls_button_select)
    "start" -> stringResource(R.string.controls_button_start)
    "dpad_up" -> stringResource(R.string.settings_gamepad_action_dpad_up)
    "dpad_down" -> stringResource(R.string.settings_gamepad_action_dpad_down)
    "dpad_left" -> stringResource(R.string.settings_gamepad_action_dpad_left)
    "dpad_right" -> stringResource(R.string.settings_gamepad_action_dpad_right)
    "left_stick_up" -> stringResource(R.string.settings_gamepad_action_left_stick_up)
    "left_stick_down" -> stringResource(R.string.settings_gamepad_action_left_stick_down)
    "left_stick_left" -> stringResource(R.string.settings_gamepad_action_left_stick_left)
    "left_stick_right" -> stringResource(R.string.settings_gamepad_action_left_stick_right)
    "right_stick_up" -> stringResource(R.string.settings_gamepad_action_right_stick_up)
    "right_stick_down" -> stringResource(R.string.settings_gamepad_action_right_stick_down)
    "right_stick_left" -> stringResource(R.string.settings_gamepad_action_right_stick_left)
    "right_stick_right" -> stringResource(R.string.settings_gamepad_action_right_stick_right)
    KeyboardMouseBindings.ACTION_QUICK_SAVE -> stringResource(R.string.emulation_quickbar_quick_save)
    KeyboardMouseBindings.ACTION_QUICK_LOAD -> stringResource(R.string.emulation_quickbar_quick_load)
    else -> actionId
}

@Composable
private fun localizedMouseButtonLabel(button: Int): String = when (button) {
    KeyboardMouseBindings.MOUSE_BUTTON_LEFT -> stringResource(R.string.settings_mouse_button_left)
    KeyboardMouseBindings.MOUSE_BUTTON_RIGHT -> stringResource(R.string.settings_mouse_button_right)
    KeyboardMouseBindings.MOUSE_BUTTON_MIDDLE -> stringResource(R.string.settings_mouse_button_middle)
    KeyboardMouseBindings.MOUSE_BUTTON_BACK -> stringResource(R.string.settings_mouse_button_back)
    KeyboardMouseBindings.MOUSE_BUTTON_FORWARD -> stringResource(R.string.settings_mouse_button_forward)
    else -> stringResource(R.string.settings_mouse_button_number, button)
}

/** Translate key names while retaining the letters, digits and symbols printed on physical keys. */
@Composable
private fun localizedKeyboardKeyLabel(keyCode: Int): String = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> stringResource(R.string.controls_button_up)
    KeyEvent.KEYCODE_DPAD_DOWN -> stringResource(R.string.controls_button_down)
    KeyEvent.KEYCODE_DPAD_LEFT -> stringResource(R.string.controls_button_left)
    KeyEvent.KEYCODE_DPAD_RIGHT -> stringResource(R.string.controls_button_right)
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> stringResource(R.string.settings_keyboard_key_enter)
    KeyEvent.KEYCODE_DEL -> stringResource(R.string.settings_keyboard_key_backspace)
    KeyEvent.KEYCODE_SPACE -> stringResource(R.string.settings_keyboard_key_space)
    KeyEvent.KEYCODE_TAB -> stringResource(R.string.settings_keyboard_key_tab)
    KeyEvent.KEYCODE_ESCAPE -> stringResource(R.string.settings_keyboard_key_escape)
    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> stringResource(R.string.settings_keyboard_key_shift)
    KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> stringResource(R.string.settings_keyboard_key_control)
    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> stringResource(R.string.settings_keyboard_key_alt)
    KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> stringResource(R.string.settings_keyboard_key_meta)
    KeyEvent.KEYCODE_MOVE_HOME -> stringResource(R.string.settings_keyboard_key_home)
    KeyEvent.KEYCODE_MOVE_END -> stringResource(R.string.settings_keyboard_key_end)
    KeyEvent.KEYCODE_PAGE_UP -> stringResource(R.string.settings_keyboard_key_page_up)
    KeyEvent.KEYCODE_PAGE_DOWN -> stringResource(R.string.settings_keyboard_key_page_down)
    KeyEvent.KEYCODE_INSERT -> stringResource(R.string.settings_keyboard_key_insert)
    KeyEvent.KEYCODE_FORWARD_DEL -> stringResource(R.string.settings_keyboard_key_delete)
    KeyEvent.KEYCODE_CAPS_LOCK -> stringResource(R.string.settings_keyboard_key_caps_lock)
    KeyEvent.KEYCODE_NUM_LOCK -> stringResource(R.string.settings_keyboard_key_num_lock)
    KeyEvent.KEYCODE_SCROLL_LOCK -> stringResource(R.string.settings_keyboard_key_scroll_lock)
    KeyEvent.KEYCODE_BREAK -> stringResource(R.string.settings_keyboard_key_pause)
    KeyEvent.KEYCODE_SYSRQ -> stringResource(R.string.settings_keyboard_key_print_screen)
    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A'.code + keyCode - KeyEvent.KEYCODE_A).toChar().toString()
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (keyCode - KeyEvent.KEYCODE_0).toString()
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> (keyCode - KeyEvent.KEYCODE_NUMPAD_0).toString()
    in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${keyCode - KeyEvent.KEYCODE_F1 + 1}"
    KeyEvent.KEYCODE_COMMA -> ","
    KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_NUMPAD_DOT -> "."
    KeyEvent.KEYCODE_GRAVE -> "`"
    KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "−"
    KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_EQUALS -> "="
    KeyEvent.KEYCODE_LEFT_BRACKET -> "["
    KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
    KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN -> "("
    KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN -> ")"
    KeyEvent.KEYCODE_BACKSLASH -> "\\"
    KeyEvent.KEYCODE_SEMICOLON -> ";"
    KeyEvent.KEYCODE_APOSTROPHE -> "'"
    KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_NUMPAD_DIVIDE -> "/"
    KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> "+"
    KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> "*"
    KeyEvent.KEYCODE_POUND -> "#"
    KeyEvent.KEYCODE_AT -> "@"
    else -> stringResource(R.string.settings_keyboard_key_number, keyCode)
}
