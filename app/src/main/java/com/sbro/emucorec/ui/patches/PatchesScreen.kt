package com.sbro.emucorec.ui.patches

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sbro.emucorec.R
import com.sbro.emucorec.data.InstalledPs3Game
import com.sbro.emucorec.data.PatchRepository
import com.sbro.emucorec.data.Ps3PatchInfo
import com.sbro.emucorec.ui.common.EmuCoreLoadingAnimation
import com.sbro.emucorec.ui.common.LocalImage
import com.sbro.emucorec.ui.common.ScreenTopBar
import com.sbro.emucorec.ui.settings.animateScrollToCenterItem
import com.sbro.emucorec.ui.theme.CardContentPadding
import com.sbro.emucorec.ui.theme.ScreenContentBottomPadding
import com.sbro.emucorec.ui.theme.ScreenHorizontalPadding
import com.sbro.emucorec.ui.theme.neon.neonShape

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatchesScreen(
    initialTitleId: String? = null,
    onMenuClick: (() -> Unit)? = null,
    onBackClick: (() -> Unit)? = null,
    viewModel: PatchesViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val resumeTitleId by rememberUpdatedState(initialTitleId ?: uiState.selectedTitleId)
    val topInset = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()

    LaunchedEffect(initialTitleId) {
        initialTitleId?.takeIf(String::isNotBlank)?.let(viewModel::selectGame)
    }

    DisposableEffect(lifecycleOwner, initialTitleId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh(resumeTitleId)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = topInset,
            bottom = ScreenContentBottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenTopBar(
                title = stringResource(R.string.patches_title),
                onBackClick = onBackClick,
                onMenuClick = onMenuClick,
                modifier = Modifier.padding(horizontal = ScreenHorizontalPadding),
                actions = {
                    IconButton(
                        onClick = viewModel::downloadPatches,
                        enabled = !uiState.isDownloading
                    ) {
                        if (uiState.isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = stringResource(R.string.patches_update),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        }

        if (uiState.isLoading && !uiState.hasLoadedOnce) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    EmuCoreLoadingAnimation()
                }
            }
        } else if (uiState.games.isEmpty()) {
            item {
                EmptyPatchesState()
            }
        } else {
            item {
                PatchGamePicker(
                    games = uiState.games,
                    selectedTitleId = uiState.selectedTitleId,
                    selectedGame = uiState.selectedGame,
                    onSelect = viewModel::selectGame
                )
            }

            if (uiState.downloadResult != null) {
                item {
                    DownloadResultBanner(
                        downloadResult = uiState.downloadResult!!,
                        modifier = Modifier.padding(horizontal = ScreenHorizontalPadding)
                    )
                }
            }

            if (uiState.patches.isEmpty() && !uiState.patchesLoading) {
                item {
                    NoPatchesHint(
                        hasDownloaded = uiState.downloadResult != null,
                        onDownload = viewModel::downloadPatches
                    )
                }
            } else if (uiState.patchesLoading && uiState.patches.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                items(uiState.patches, key = Ps3PatchInfo::identityKey) { patch ->
                    PatchCard(
                        patch = patch,
                        enabled = patch.identityKey !in uiState.togglingPatchKeys,
                        onToggle = { viewModel.togglePatch(patch) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyPatchesState() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 48.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Build,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.patches_empty_title),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.patches_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun NoPatchesHint(
    hasDownloaded: Boolean,
    onDownload: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 36.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.CloudDownload,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(
                    if (hasDownloaded) R.string.patches_no_patches_for_game
                    else R.string.patches_hint_update
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (!hasDownloaded) {
                Surface(
                    onClick = onDownload,
                    shape = neonShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = stringResource(R.string.patches_update),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadResultBanner(
    downloadResult: PatchRepository.DownloadResult,
    modifier: Modifier = Modifier
) {
    val message = when (downloadResult) {
        is PatchRepository.DownloadResult.UpToDate -> stringResource(R.string.patches_up_to_date)
        is PatchRepository.DownloadResult.Success -> stringResource(R.string.patches_import_success, downloadResult.patchesImported)
        is PatchRepository.DownloadResult.Error -> stringResource(R.string.patches_import_error, downloadResult.message)
    }
    val isError = downloadResult is PatchRepository.DownloadResult.Error
    val containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.secondaryContainer
    val contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSecondaryContainer

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = neonShape(12.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PatchGamePicker(
    games: List<InstalledPs3Game>,
    selectedTitleId: String?,
    selectedGame: InstalledPs3Game?,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.patches_choose_game),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = selectedGame?.let { "${it.title} · ${it.titleId}" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        val listState = rememberLazyListState()
        LaunchedEffect(selectedTitleId, games) {
            val selectedIndex = games.indexOfFirst { it.titleId.equals(selectedTitleId, ignoreCase = true) }
            if (selectedIndex >= 0) {
                listState.animateScrollToCenterItem(selectedIndex)
            }
        }
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = ScreenHorizontalPadding)
        ) {
            items(games, key = { it.titleId }) { game ->
                val selected = game.titleId == selectedTitleId
                Surface(
                    onClick = { onSelect(game.titleId) },
                    shape = neonShape(18.dp),
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.68f) else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = neonShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        ) {
                            LocalImage(
                                path = game.iconPath,
                                contentDescription = game.title,
                                fallbackLabel = game.title,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Column(modifier = Modifier.widthIn(min = 120.dp, max = 160.dp).height(46.dp)) {
                            Text(
                                text = game.title,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = game.titleId,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatchCard(
    patch: Ps3PatchInfo,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenHorizontalPadding),
        shape = neonShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier.padding(CardContentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = patch.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (patch.author.isNotBlank()) {
                        Text(
                            text = patch.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Switch(
                    checked = patch.enabled,
                    enabled = enabled,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            if (patch.notes.isNotBlank()) {
                Text(
                    text = patch.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (patch.version.isNotBlank()) {
                    PatchBadge(label = stringResource(R.string.patches_version, patch.version))
                }
                if (patch.appVersion.isNotBlank() && !patch.appVersion.equals("all", ignoreCase = true)) {
                    PatchBadge(label = stringResource(R.string.patches_app_version, patch.appVersion))
                }
                if (patch.game.isNotBlank()) {
                    PatchBadge(label = patch.game)
                }
            }
        }
    }
}

@Composable
private fun PatchBadge(label: String) {
    Surface(
        shape = neonShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
