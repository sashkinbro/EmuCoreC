package com.sbro.emucorec.ui.emulation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.sbro.emucorec.core.SaveStateSlot
import com.sbro.emucorec.ui.theme.neon.neonShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SaveStatePreview(slot: SaveStateSlot?, modifier: Modifier = Modifier) {
    val palette = emulationMenuPalette()
    // Slot screenshots are replaced in place. Do not reuse the library artwork cache.
    val bitmap by produceState<Bitmap?>(null, slot?.thumbnailPath, slot?.thumbnailRevision) {
        value = null
        value = withContext(Dispatchers.IO) {
            slot?.thumbnailPath?.let { path -> runCatching {
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 1 })
            }.getOrNull() }
        }
    }
    Box(
        modifier = modifier.testTag("save-state-preview").clip(neonShape(12.dp)).background(palette.row),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit)
        } ?: Icon(Icons.Rounded.Save, contentDescription = null,
            tint = palette.textSecondary.copy(alpha = 0.5f), modifier = Modifier.size(36.dp))
    }
}
