@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.sbro.emucorec.ui.savestates

import com.sbro.emucorec.ui.common.EmptyStateCard
import com.sbro.emucorec.ui.common.appTonalButtonColors
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import com.sbro.emucorec.ui.common.AppAlertDialog as AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sbro.emucorec.R
import com.sbro.emucorec.core.SaveStateOutcome
import com.sbro.emucorec.core.SaveStateSlot
import com.sbro.emucorec.core.Ps3LaunchBridge
import com.sbro.emucorec.core.formatSaveStateSize
import com.sbro.emucorec.core.formatSaveStateTimestamp
import com.sbro.emucorec.ui.common.LocalImage
import com.sbro.emucorec.ui.common.NavigationBackButton
import com.sbro.emucorec.ui.common.EmuCoreLoadingAnimation
import com.sbro.emucorec.ui.common.ScreenTopBarSurface
import com.sbro.emucorec.ui.common.rememberDebouncedClick
import com.sbro.emucorec.ui.common.rememberDelayedLoading
import com.sbro.emucorec.ui.theme.ScreenContentBottomPadding
import com.sbro.emucorec.ui.theme.ScreenHorizontalPadding
import com.sbro.emucorec.ui.theme.neon.neonButtonShape
import com.sbro.emucorec.ui.theme.neon.neonShape

@Composable
fun SaveStatesScreen(
    focusTitleId: String?,
    onBackClick: () -> Unit,
    viewModel: SaveStatesViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val topInset = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val backClick = rememberDebouncedClick(onClick = onBackClick)
    var pendingDelete by remember { mutableStateOf<Pair<SaveStatesGame, SaveStateSlot>?>(null) }

    val deletedMessage = stringResource(R.string.emulation_savestate_deleted_toast)
    val failedMessage = stringResource(R.string.emulation_savestate_failed_toast)
    val launchFailedMessage = stringResource(R.string.game_launch_failed)
    val launchFirmwareMessage = stringResource(R.string.game_launch_requires_firmware)
    val launchUnavailableMessage = stringResource(R.string.emulation_savestate_load_starts_game)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, focusTitleId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh(focusTitleId)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.refresh(focusTitleId)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(uiState.refreshFailed) {
        if (uiState.refreshFailed) Toast.makeText(context, failedMessage, Toast.LENGTH_LONG).show()
    }

    fun describe(result: SaveStateOutcome, successMessage: String): String =
        if (result == SaveStateOutcome.Success) successMessage else failedMessage

    fun launchResultMessage(result: Ps3LaunchBridge.LaunchResult): String = when (result) {
        Ps3LaunchBridge.LaunchResult.Success -> launchUnavailableMessage
        Ps3LaunchBridge.LaunchResult.MissingFirmware -> launchFirmwareMessage
        Ps3LaunchBridge.LaunchResult.Failure -> launchFailedMessage
    }

    fun loadState(game: SaveStatesGame, slot: SaveStateSlot) {
        viewModel.launchWithState(game.titleId, slot.slot) { result ->
            if (result != Ps3LaunchBridge.LaunchResult.Success) {
                Toast.makeText(context, launchResultMessage(result), Toast.LENGTH_LONG).show()
            }
        }
    }

    // Only the genuinely empty first load shows the spinner, and only if it
    // takes long enough to see; refreshing with slots already on screen just
    // updates the list. This is what made the animation "blink" here.
    val showLoading = rememberDelayedLoading(uiState.isLoading && !uiState.hasLoadedOnce)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
        contentPadding = PaddingValues(
            start = ScreenHorizontalPadding,
            end = ScreenHorizontalPadding,
            top = topInset,
            bottom = ScreenContentBottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ScreenTopBarSurface {
                NavigationBackButton(
                    onClick = backClick,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.nav_save_states),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = { viewModel.refresh(focusTitleId) }) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.savestate_manager_refresh),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        when {
            showLoading -> item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    EmuCoreLoadingAnimation()
                }
            }

            uiState.games.isEmpty() && (!uiState.isLoading || uiState.hasLoadedOnce) -> item {
                EmptySaveStatesCard()
            }

            else -> items(uiState.games, key = { it.titleId }) { game ->
                SaveStatesGameCard(
                    game = game,
                    running = false,
                    busyKey = uiState.busyKey,
                    loadUnavailableMessage = launchUnavailableMessage,
                    onLoad = { slot -> loadState(game, slot) },
                    onDelete = { slot -> pendingDelete = game to slot }
                )
            }
        }
    }

    pendingDelete?.let { (game, slot) ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(text = stringResource(R.string.emulation_savestate_confirm_delete_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.emulation_savestate_confirm_delete_body,
                        saveStateSlotName(slot)
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        viewModel.delete(game.titleId, slot.slot) { result ->
                            Toast.makeText(context, describe(result, deletedMessage), Toast.LENGTH_LONG).show()
                        }
                    }
                ) {
                    Text(text = stringResource(R.string.emulation_savestate_delete_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(text = stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun SaveStatesGameCard(
    game: SaveStatesGame,
    running: Boolean,
    busyKey: String?,
    loadUnavailableMessage: String,
    onLoad: (SaveStateSlot) -> Unit,
    onDelete: (SaveStateSlot) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        tonalElevation = 1.dp,
        shadowElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = game.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = game.titleId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            game.slots.forEach { slot ->
                SaveStateSlotRow(
                    slot = slot,
                    running = running,
                    busy = busyKey == "${game.titleId}:${slot.slot}",
                    loadUnavailableMessage = loadUnavailableMessage,
                    onLoad = { onLoad(slot) },
                    onDelete = { onDelete(slot) }
                )
            }
        }
    }
}

@Composable
private fun SaveStateSlotRow(
    slot: SaveStateSlot,
    running: Boolean,
    busy: Boolean,
    loadUnavailableMessage: String,
    onLoad: () -> Unit,
    onDelete: () -> Unit
) {
    val slotName = saveStateSlotName(slot)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = neonShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = neonShape(10.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    key(slot.timestamp, slot.sizeBytes) { LocalImage(
                        path = slot.thumbnailPath,
                        contentDescription = slotName,
                        fallbackLabel = slotName,
                        modifier = Modifier
                            .width(96.dp)
                            .height(54.dp)
                            .clip(neonShape(10.dp))
                    ) }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = slotName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = formatSaveStateTimestamp(slot.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatSaveStateSize(slot.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                }
            }

            if (!running) {
                Text(
                    text = loadUnavailableMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    shape = neonButtonShape(),
                    colors = appTonalButtonColors(),
                    onClick = onLoad,
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.emulation_savestate_load_action))
                }
                OutlinedButton(
                    shape = neonButtonShape(),
                    onClick = onDelete,
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.emulation_savestate_delete_action))
                }
            }
        }
    }
}

@Composable
private fun EmptySaveStatesCard() {
    EmptyStateCard(
        icon = Icons.Rounded.Save,
        title = stringResource(R.string.savestate_manager_empty_title),
        body = stringResource(R.string.savestate_manager_empty_body),
    )
}

@Composable
private fun saveStateSlotName(slot: SaveStateSlot): String =
    if (slot.isQuick) {
        stringResource(R.string.emulation_savestate_quick_label)
    } else {
        stringResource(R.string.emulation_savestate_slot_label, slot.slot)
    }
