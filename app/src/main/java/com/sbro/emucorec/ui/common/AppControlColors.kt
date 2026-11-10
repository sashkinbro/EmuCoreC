package com.sbro.emucorec.ui.common

import androidx.compose.material3.ButtonDefaults
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

/**
 * Quiet secondary action. M3's tonal button paints itself with `secondaryContainer`, which in
 * this palette is the purple accent, so a neutral variant fill with primary text is used instead.
 */
@Composable
fun appTonalButtonColors() = ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    contentColor = MaterialTheme.colorScheme.primary,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
)
