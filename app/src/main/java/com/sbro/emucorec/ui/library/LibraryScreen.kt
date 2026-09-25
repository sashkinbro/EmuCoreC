package com.sbro.emucorec.ui.library

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sbro.emucorec.R
import com.sbro.emucorec.data.LibraryLayoutPreferences
import com.sbro.emucorec.data.Ps3CatalogRepository
import com.sbro.emucorec.ui.common.LocalImage
import com.sbro.emucorec.ui.common.UrlImage
import com.sbro.emucorec.ui.common.CustomizationBackground
import com.sbro.emucorec.ui.common.NavigationMenuButton
import com.sbro.emucorec.ui.common.ScreenTopBarSurface
import com.sbro.emucorec.ui.common.EmuCoreLoadingAnimation
import com.sbro.emucorec.ui.common.rememberDebouncedClick
import com.sbro.emucorec.ui.theme.CardContentPadding
import com.sbro.emucorec.ui.theme.LocalCustomizationSettings
import com.sbro.emucorec.ui.theme.ScreenContentBottomPadding
import com.sbro.emucorec.ui.theme.ScreenHorizontalPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.sbro.emucorec.ui.theme.neon.neonShape
import com.sbro.emucorec.ui.theme.neon.neonButtonShape

private enum class LibraryLayoutMode {
    LIST,
    GRID
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    onLaunchGame: (String) -> Unit,
    onOpenSaveManager: (String) -> Unit,
    onOpenGameManager: (String) -> Unit,
    onOpenPatches: (String) -> Unit,
    onOpenPlayTime: (String) -> Unit,
    onOpenAchievements: (String) -> Unit,
    onOpenCatalogEntry: (Long) -> Unit,
    onMenuClick: (() -> Unit)? = null,
    viewModel: LibraryViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val catalogRepository = remember(context) { Ps3CatalogRepository(context.applicationContext) }
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val customization = LocalCustomizationSettings.current
    val refreshClick = rememberDebouncedClick(onClick = viewModel::refresh)
    val layoutPreferences = remember(context) { LibraryLayoutPreferences(context) }
    var layoutMode by rememberSaveable {
        mutableStateOf(
            LibraryLayoutMode.entries.firstOrNull { it.name == layoutPreferences.layoutMode }
                ?: LibraryLayoutMode.LIST
        )
    }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var deleteGameId by remember { mutableStateOf<String?>(null) }

    val topInset = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val gameCountSubtitle = pluralStringResource(
        R.plurals.library_game_count,
        uiState.items.size,
        uiState.items.size
    )
    val deleteGameLabel = stringResource(R.string.detail_delete_game)
    val manageSaveDataLabel = stringResource(R.string.save_manager_open_for_game)
    val manageGameSettingsLabel = stringResource(R.string.game_manager_open_for_game)
    val patchesLabel = stringResource(R.string.patches_open_for_game)
    val playTimeLabel = stringResource(R.string.play_time_open_for_game)
    val achievementsLabel = stringResource(R.string.achievements_open_for_game)
    val openCatalogLabel = stringResource(R.string.catalog_open_for_game)
    val catalogSerialNotFound = stringResource(R.string.catalog_serial_not_found)
    val addShortcutLabel = stringResource(R.string.library_add_shortcut)
    val shortcutRequestedMessage = stringResource(R.string.library_shortcut_requested)
    val shortcutUnsupportedMessage = stringResource(R.string.library_shortcut_unsupported)
    val shortcutFailedMessage = stringResource(R.string.library_shortcut_failed)
    val deleteGameConfirmTitle = stringResource(R.string.detail_delete_game_confirm_title)
    val deleteGameConfirmBody = stringResource(R.string.detail_delete_game_confirm_body)
    val deleteGameFailedMessage = stringResource(R.string.detail_delete_game_failed)
    val containerWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val gridColumns = remember(containerWidthDp, customization.coverSizePercent) {
        LibraryGridSizing.columnsForWidth(
            containerWidthDp.value,
            customization.coverSizePercent
        )
    }
    val openCatalogBySerial: (String) -> Unit = { titleId ->
        coroutineScope.launch {
            val match = withContext(Dispatchers.IO) { catalogRepository.findBySerial(titleId) }
            if (match != null) {
                onOpenCatalogEntry(match.igdbId)
            } else {
                Toast.makeText(context, catalogSerialNotFound, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        CustomizationBackground(
            path = customization.backgroundPath,
            mimeType = customization.backgroundMimeType,
            modifier = Modifier.matchParentSize()
        )
        if (customization.backgroundPath != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f))
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ScreenHorizontalPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = topInset + 8.dp,
                bottom = ScreenContentBottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            item {
                ScreenTopBarSurface(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onMenuClick != null) {
                            NavigationMenuButton(
                                onClick = onMenuClick,
                                modifier = Modifier.padding(end = 14.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.nav_library),
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = gameCountSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                searchExpanded = !searchExpanded
                                if (!searchExpanded) viewModel.updateQuery("")
                            }
                        ) {
                            Icon(
                                imageVector = if (searchExpanded) Icons.Rounded.Close else Icons.Rounded.Search,
                                contentDescription = stringResource(R.string.library_search_hint),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            layoutMode = if (layoutMode == LibraryLayoutMode.LIST) LibraryLayoutMode.GRID else LibraryLayoutMode.LIST
                            layoutPreferences.layoutMode = layoutMode.name
                        }) {
                            Icon(
                                imageVector = if (layoutMode == LibraryLayoutMode.LIST) Icons.Rounded.ViewModule else Icons.AutoMirrored.Rounded.ViewList,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = refreshClick) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.library_refresh),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

        item {
            AnimatedVisibility(
                visible = searchExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = viewModel::updateQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.library_search_hint)) },
                    singleLine = true,
                    shape = neonShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }

        if (uiState.isLoading && !uiState.hasLoadedOnce) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    EmuCoreLoadingAnimation()
                }
            }
        } else if (uiState.items.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 360.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.widthIn(max = 520.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.library_empty_title),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.library_empty_body),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                            textAlign = TextAlign.Center
                        )
                        Button(shape = neonButtonShape(), onClick = refreshClick) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.library_refresh))
                        }
                    }
                }
            }
        } else if (layoutMode == LibraryLayoutMode.LIST) {
            items(uiState.items, key = { it.titleId }) { game ->
                val selectGameClick = rememberDebouncedClick { onLaunchGame(game.titleId) }
                val shape = neonShape(24.dp)
                var menuExpanded by remember(game.titleId) { mutableStateOf(false) }
                Box {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .combinedClickable(
                                onClick = selectGameClick,
                                onLongClick = { menuExpanded = true }
                            ),
                        shape = shape,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp,
                        shadowElevation = 8.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f))
                    ) {
                        Row(
                            modifier = Modifier.padding(CardContentPadding),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LibraryGameArtwork(
                                localPath = game.iconPath,
                                coverUrl = game.catalogCoverUrl,
                                title = game.title,
                                modifier = Modifier.width(66.dp),
                                artworkAspectRatio = 3f / 4f
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = game.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = game.titleId,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = game.version ?: stringResource(R.string.common_not_available),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(addShortcutLabel) },
                            onClick = {
                                val message = when (GameShortcutInstaller.requestPinnedShortcut(context, game.titleId, game.title, game.iconPath)) {
                                    GameShortcutInstaller.Result.Requested -> shortcutRequestedMessage
                                    GameShortcutInstaller.Result.Unsupported -> shortcutUnsupportedMessage
                                    GameShortcutInstaller.Result.Failed -> shortcutFailedMessage
                                }
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(openCatalogLabel) },
                            onClick = {
                                openCatalogBySerial(game.titleId)
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(manageGameSettingsLabel) },
                            onClick = {
                                onOpenGameManager(game.titleId)
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(patchesLabel) },
                            onClick = {
                                onOpenPatches(game.titleId)
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Build,
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(playTimeLabel) },
                            onClick = {
                                onOpenPlayTime(game.titleId)
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.QueryStats,
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(achievementsLabel) },
                            onClick = {
                                onOpenAchievements(game.titleId)
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.EmojiEvents,
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(manageSaveDataLabel) },
                            onClick = {
                                onOpenSaveManager(game.titleId)
                                menuExpanded = false
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Save,
                                    contentDescription = null
                                )
                            }
                        )
                        if (!game.isCustomFolderGame) {
                            DropdownMenuItem(
                                text = { Text(deleteGameLabel) },
                                onClick = {
                                    deleteGameId = game.titleId
                                    menuExpanded = false
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = null
                                    )
                                }
                            )
                        }
                    }
                }
            }
        } else {
            items(
                items = uiState.items.chunked(gridColumns),
                key = { row -> row.firstOrNull()?.titleId ?: row.hashCode().toString() }
            ) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowItems.forEach { game ->
                        val selectGameClick = rememberDebouncedClick { onLaunchGame(game.titleId) }
                        val shape = neonShape(24.dp)
                        var menuExpanded by remember(game.titleId) { mutableStateOf(false) }
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(shape)
                                    .combinedClickable(
                                        onClick = selectGameClick,
                                        onLongClick = { menuExpanded = true }
                                    ),
                                shape = shape,
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 3.dp,
                                shadowElevation = 8.dp,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    LibraryGameArtwork(
                                        localPath = game.iconPath,
                                        coverUrl = game.catalogCoverUrl,
                                        title = game.title,
                                        modifier = Modifier.fillMaxWidth(),
                                        artworkAspectRatio = 3f / 4f
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(42.dp),
                                            contentAlignment = Alignment.BottomStart
                                        ) {
                                        Text(
                                            text = game.title,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        }
                                    }
                                }
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(addShortcutLabel) },
                                    onClick = {
                                        val message = when (GameShortcutInstaller.requestPinnedShortcut(context, game.titleId, game.title, game.iconPath)) {
                                            GameShortcutInstaller.Result.Requested -> shortcutRequestedMessage
                                            GameShortcutInstaller.Result.Unsupported -> shortcutUnsupportedMessage
                                            GameShortcutInstaller.Result.Failed -> shortcutFailedMessage
                                        }
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                        menuExpanded = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Add,
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(openCatalogLabel) },
                                    onClick = {
                                        openCatalogBySerial(game.titleId)
                                        menuExpanded = false
                                    },
                                    leadingIcon = {
                                        Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(manageGameSettingsLabel) },
                                    onClick = {
                                        onOpenGameManager(game.titleId)
                                        menuExpanded = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Tune,
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(patchesLabel) },
                                    onClick = {
                                        onOpenPatches(game.titleId)
                                        menuExpanded = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Build,
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(playTimeLabel) },
                                    onClick = {
                                        onOpenPlayTime(game.titleId)
                                        menuExpanded = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.QueryStats,
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(achievementsLabel) },
                                    onClick = {
                                        onOpenAchievements(game.titleId)
                                        menuExpanded = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.EmojiEvents,
                                            contentDescription = null
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(manageSaveDataLabel) },
                                    onClick = {
                                        onOpenSaveManager(game.titleId)
                                        menuExpanded = false
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Save,
                                            contentDescription = null
                                        )
                                    }
                                )
                                if (!game.isCustomFolderGame) {
                                    DropdownMenuItem(
                                        text = { Text(deleteGameLabel) },
                                        onClick = {
                                            deleteGameId = game.titleId
                                            menuExpanded = false
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Rounded.Delete,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                    repeat(gridColumns - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        }
    }

    if (deleteGameId != null) {
        AlertDialog(
            onDismissRequest = { deleteGameId = null },
            title = { Text(deleteGameConfirmTitle) },
            text = { Text(deleteGameConfirmBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targetGameId = deleteGameId
                        if (targetGameId != null) {
                            viewModel.deleteInstalledGame(targetGameId) { deleted ->
                                if (!deleted) {
                                    Toast.makeText(context, deleteGameFailedMessage, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        deleteGameId = null
                    }
                ) {
                    Text(deleteGameLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteGameId = null }) {
                    Text(stringResource(R.string.install_dialog_close))
                }
            }
        )
    }
}

@Composable
private fun LibraryGameArtwork(
    localPath: String?,
    coverUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    artworkAspectRatio: Float = 3f / 4f
) {
    Surface(
        modifier = modifier,
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        val imageModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(artworkAspectRatio)
        if (!coverUrl.isNullOrBlank()) {
            UrlImage(
                imageUrl = coverUrl,
                contentDescription = title,
                fallbackLabel = title,
                fallbackPath = localPath,
                modifier = imageModifier
            )
        } else {
            LocalImage(
                path = localPath,
                contentDescription = title,
                fallbackLabel = title,
                modifier = imageModifier
            )
        }
    }
}



