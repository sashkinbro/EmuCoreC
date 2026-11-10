package com.sbro.emucorec.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sbro.emucorec.R
import com.sbro.emucorec.ui.theme.neon.neonShape

/** EmuCoreX's grouped header and rounded library-options menu. */
@Composable
internal fun LibraryHeaderActions(
    optionsExpanded: Boolean,
    onOptionsExpandedChange: (Boolean) -> Unit,
    refreshing: Boolean,
    searchExpanded: Boolean,
    onToggleSearch: () -> Unit,
    layoutMode: LibraryLayoutMode,
    onToggleLayout: () -> Unit,
    sortOption: LibrarySortOption,
    onSortSelected: (LibrarySortOption) -> Unit,
    onRefresh: () -> Unit,
) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current
    val menuMaxHeight = with(density) { (window.containerSize.height.toDp() - 36.dp).coerceAtLeast(180.dp) }
    val viewIcon = if (layoutMode == LibraryLayoutMode.LIST) Icons.Rounded.ViewModule else Icons.AutoMirrored.Rounded.ViewList
    val viewLabel = stringResource(if (layoutMode == LibraryLayoutMode.LIST) R.string.home_view_grid else R.string.home_view_list)
    Surface(
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(modifier = Modifier.size(42.dp), onClick = onToggleSearch) {
                Icon(if (searchExpanded) Icons.Rounded.Close else Icons.Rounded.Search,
                    stringResource(R.string.home_search),
                    tint = if (searchExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HeaderActionDivider()
            IconButton(modifier = Modifier.size(42.dp), onClick = onToggleLayout) {
                Icon(viewIcon, viewLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HeaderActionDivider()
            Box {
                IconButton(
                    modifier = Modifier.size(42.dp).testTag("library-options"),
                    onClick = { onOptionsExpandedChange(true) },
                ) {
                    if (refreshing) CircularProgressIndicator(modifier = Modifier.size(19.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Icon(Icons.Rounded.MoreVert, stringResource(R.string.home_library_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = optionsExpanded,
                    onDismissRequest = { onOptionsExpandedChange(false) },
                    modifier = Modifier.widthIn(min = 248.dp, max = 310.dp).heightIn(max = menuMaxHeight),
                    shape = neonShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 12.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                ) {
                    LibraryOptionItem(stringResource(R.string.home_search), Icons.Rounded.Search,
                        selected = searchExpanded, onClick = { onOptionsExpandedChange(false); onToggleSearch() })
                    LibraryOptionItem(viewLabel, viewIcon,
                        onClick = { onOptionsExpandedChange(false); onToggleLayout() })
                    LibraryOptionItem(stringResource(R.string.library_refresh), Icons.Rounded.Refresh,
                        emphasized = true, enabled = !refreshing,
                        onClick = { onOptionsExpandedChange(false); onRefresh() })
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f))
                    LibrarySortOption.entries.forEach { option ->
                        val label = when (option) {
                            LibrarySortOption.TITLE_ASC -> R.string.home_sort_title_asc
                            LibrarySortOption.TITLE_DESC -> R.string.home_sort_title_desc
                            LibrarySortOption.RECENT_DESC -> R.string.home_sort_recent_desc
                            LibrarySortOption.RECENT_ASC -> R.string.home_sort_recent_asc
                            LibrarySortOption.SIZE_DESC -> R.string.home_sort_size_desc
                            LibrarySortOption.SIZE_ASC -> R.string.home_sort_size_asc
                        }
                        val icon = when (option) {
                            LibrarySortOption.TITLE_ASC, LibrarySortOption.TITLE_DESC -> Icons.Rounded.SortByAlpha
                            LibrarySortOption.RECENT_DESC, LibrarySortOption.RECENT_ASC -> Icons.Rounded.History
                            LibrarySortOption.SIZE_DESC, LibrarySortOption.SIZE_ASC -> Icons.Rounded.Storage
                        }
                        LibraryOptionItem(stringResource(label), icon, selected = sortOption == option,
                            onClick = { onSortSelected(option); onOptionsExpandedChange(false) })
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderActionDivider() {
    Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)))
}

/** Same icon tiles, label weights and selected mark as X's GameContextMenuItem. */
@Composable
private fun LibraryOptionItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    emphasized: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val contentAlpha = if (enabled) 1f else 0.38f
    DropdownMenuItem(
        text = {
            Text(text, style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium),
                color = (if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface).copy(alpha = contentAlpha),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = {
            val tile = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f)
            Box(Modifier.size(34.dp).clip(neonShape(11.dp)).background(tile.copy(alpha = tile.alpha * contentAlpha)),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, modifier = Modifier.size(19.dp),
                    tint = (if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = contentAlpha))
            }
        },
        trailingIcon = if (selected) {{
            Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }} else null,
        contentPadding = PaddingValues(horizontal = 12.dp),
    )
}
