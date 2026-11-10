package com.sbro.emucorec.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp

/**
 * Widens a horizontally scrollable row into the surrounding padding on both sides, so the
 * scroll viewport reaches the container edge while the first and last item still rest on the
 * content inset. Put it on the scroll container, before [androidx.compose.foundation.horizontalScroll],
 * and add the same inset as padding *inside* the scrolling content - padding on the content,
 * not on the container. This is the arrangement Settings and the game menu already use.
 */
internal fun Modifier.horizontalBleed(bleed: Dp): Modifier = layout { measurable, constraints ->
    val bleedPx = bleed.roundToPx()
    val looseConstraints = constraints.copy(
        maxWidth = (constraints.maxWidth + bleedPx * 2).coerceAtLeast(0)
    )
    val placeable = measurable.measure(looseConstraints)
    layout(constraints.maxWidth, placeable.height) {
        placeable.placeRelative(-bleedPx, 0)
    }
}
