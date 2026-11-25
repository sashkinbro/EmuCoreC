package com.sbro.emucorec.ui.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Shape

// EmuCoreC has no gamepad/TV navigation layer yet. These keep the shared Hub and Store screens (ported
// from EmuCoreX) source-compatible with it: focus highlighting and shoulder-button tab switching are no-ops.

enum class GamepadFocusHighlightMode {
    ConnectedGamepadOnly,
    Always
}

@Suppress("UNUSED_PARAMETER")
fun Modifier.gamepadFocusableCard(
    enabled: Boolean = true,
    shape: Shape? = null,
    interactionSource: MutableInteractionSource? = null,
    addFocusTarget: Boolean = true,
    focusHighlightMode: GamepadFocusHighlightMode = GamepadFocusHighlightMode.ConnectedGamepadOnly,
    showIdleBorder: Boolean = true
): Modifier = this

@Suppress("UNUSED_PARAMETER")
fun Modifier.tvGamepadFocusableCard(
    shape: Shape? = null,
    interactionSource: MutableInteractionSource? = null,
    addFocusTarget: Boolean = true
): Modifier = this

fun Modifier.tvFocusGroup(): Modifier = this

@Suppress("UNUSED_PARAMETER")
@Composable
fun ProvideGamepadShoulderActions(
    enabled: Boolean = true,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) = Unit

@Suppress("UNUSED_PARAMETER")
@Composable
fun RequestFocusOnResume(
    focusRequester: FocusRequester,
    enabled: Boolean = true
) = Unit
