package com.sbro.emucorec.ui.common

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Keep pinned tabs clear of the camera and the landscape screen edge. */
@Composable
internal fun stickyTabTopInset(): Dp {
    val cutout = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
    return if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT) cutout else 16.dp
}

@Composable
internal fun StickyTabHeader(
    showDivider: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dividerAlpha by animateFloatAsState(
        targetValue = if (showDivider) 0.45f else 0f,
        animationSpec = tween(180),
        label = "stickyTabDivider"
    )
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
        Column {
            content()
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = dividerAlpha))
        }
    }
}

@Composable
internal fun rememberStickyTabHeaderPinned(state: LazyListState, key: Any): Boolean {
    val pinned by remember(state, key) {
        derivedStateOf {
            state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
                ?.offset?.let { it <= state.layoutInfo.viewportStartOffset } == true
        }
    }
    return pinned
}
