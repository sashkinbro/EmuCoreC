package com.sbro.emucorec.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable

/** EmuCoreX's blue controls with an explicit, visible neutral off state. */
@Composable
fun appSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.primary,
    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
    checkedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
)

@Composable
fun appSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = MaterialTheme.colorScheme.primary,
    inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
    activeTickColor = MaterialTheme.colorScheme.surface,
    inactiveTickColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
)
