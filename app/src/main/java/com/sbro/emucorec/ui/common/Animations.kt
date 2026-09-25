package com.sbro.emucorec.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sbro.emucorec.ui.theme.neon.neonShape

@Composable
fun EmuCoreLoadingAnimation(
    modifier: Modifier = Modifier,
    size: Dp = 110.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "EmuCoreCLoading")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "coreRotation"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "centerPulse"
    )

    val glowOpacity by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(36.dp)
    ) {
        Text(
            text = "EMUCOREC",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 8.sp,
                color = color.copy(alpha = glowOpacity)
            )
        )

        Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(size * 1.15f)
                    .graphicsLayer {
                        scaleX = pulseScale * 1.35f
                        scaleY = pulseScale * 1.35f
                        alpha = glowOpacity * 0.45f
                    }
                    .background(color.copy(alpha = 0.25f), CircleShape)
            )

            Box(
                modifier = Modifier
                    .size(size * 0.90f)
                    .graphicsLayer { rotationZ = rotation }
                    .border(5.dp, color.copy(alpha = 0.90f), neonShape(22.dp))
            )

            Box(
                modifier = Modifier
                    .size(size * 0.68f)
                    .graphicsLayer { rotationZ = -rotation * 1.7f }
                    .border(3.5.dp, color.copy(alpha = 0.65f), neonShape(16.dp))
            )

            Text(
                text = "C",
                fontSize = (size.value * 0.52f).sp,
                fontWeight = FontWeight.Black,
                color = color,
                modifier = Modifier.graphicsLayer {
                    val baseScale = 1.35f
                    scaleX = pulseScale * baseScale
                    scaleY = pulseScale * baseScale
                }
            )
        }
    }
}
