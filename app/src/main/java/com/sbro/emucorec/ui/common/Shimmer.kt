package com.sbro.emucorec.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

fun Modifier.shimmer(
    showShimmer: Boolean = true
): Modifier = composed {
    if (!showShimmer) return@composed this
    // The infinite transition exists only while shimmering, so placeholders that
    // stopped shimmering no longer keep an animation clock running.
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val translateAnimation by infiniteTransition.animateFloat(
        initialValue = -800f,
        targetValue = 1600f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val baseColor = remember(surfaceVariant) { surfaceVariant.copy(alpha = 0.3f) }
    val shimmerColors = remember(surfaceVariant) {
        listOf(
            surfaceVariant.copy(alpha = 0.5f),
            surfaceVariant.copy(alpha = 0.9f),
            surfaceVariant.copy(alpha = 0.5f),
        )
    }
    this.background(baseColor)
        .drawBehind {
            // Read in the draw phase: the animation redraws only, no recomposition.
            val t = translateAnimation
            drawRect(
                Brush.linearGradient(
                    colors = shimmerColors,
                    start = Offset(t, t),
                    end = Offset(t + 400f, t + 400f)
                )
            )
        }
}
